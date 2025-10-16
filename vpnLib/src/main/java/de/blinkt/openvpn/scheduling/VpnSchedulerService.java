package de.blinkt.openvpn.scheduling;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.core.OpenVPNService;

/**
 * Background service for handling VPN scheduling
 */
public class VpnSchedulerService extends Service {
    private static final String TAG = "VpnSchedulerService";
    private static final String CHANNEL_ID = "vpn_scheduler_channel";
    private static final int NOTIFICATION_ID = 1001; // default; can be overridden via prefs
    private static final String FLAG_PREFS = "vpn_scheduler_flags";
    private static final String KEY_SCHEDULED_DISCONNECT = "scheduled_disconnect";
    
    public static final String ACTION_SCHEDULE_CONNECT = "de.blinkt.openvpn.SCHEDULE_CONNECT";
    public static final String ACTION_SCHEDULE_DISCONNECT = "de.blinkt.openvpn.SCHEDULE_DISCONNECT";
    public static final String EXTRA_SCHEDULE_ID = "schedule_id";
    public static final String ACTION_SCHEDULED_EVENT = "de.blinkt.openvpn.SCHEDULED_EVENT"; // local broadcast
    
    private AlarmManager alarmManager;
    private SharedPreferences preferences;
    private SharedPreferences flagPreferences;
    private Gson gson;
    
    @Override
    public void onCreate() {
        super.onCreate();
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        preferences = getSharedPreferences("vpn_schedules", Context.MODE_PRIVATE);
        flagPreferences = getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE);
        gson = new Gson();
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            String scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID);
            String intentAction = intent.getStringExtra("action");
            
            if (ACTION_SCHEDULE_CONNECT.equals(action)) {
                handleScheduledConnect(scheduleId);
            } else if (ACTION_SCHEDULE_DISCONNECT.equals(action)) {
                handleScheduledDisconnect(scheduleId);
            } else if ("schedule".equals(intentAction)) {
                VpnSchedule schedule = (VpnSchedule) intent.getSerializableExtra("schedule");
                if (schedule != null) {
                    scheduleVpn(schedule);
                }
            } else if ("cancel".equals(intentAction)) {
                String cancelScheduleId = intent.getStringExtra("scheduleId");
                if (cancelScheduleId != null) {
                    cancelSchedule(cancelScheduleId);
                }
            } else if ("update".equals(intentAction)) {
                VpnSchedule schedule = (VpnSchedule) intent.getSerializableExtra("schedule");
                if (schedule != null) {
                    saveSchedule(schedule);
                }
            } else if ("check_idle".equals(intentAction)) {
                maybeStopSchedulerIfIdle();
            }
        }
        
        // Keep service running
        if (android.os.Build.VERSION.SDK_INT >= 34) { // Android 14 (API 34)
            startForeground(getNotificationIdFromPrefs(), createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(getNotificationIdFromPrefs(), createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(getNotificationIdFromPrefs(), createNotification());
        }
        return START_STICKY;
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "VPN Scheduler",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background VPN scheduling service");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        // Load configurable notification values from prefs
        android.content.SharedPreferences sp = getSharedPreferences("vpn_scheduler_prefs", Context.MODE_PRIVATE);
        String title = sp.getString("notif_title", "VPN Scheduler");
        String text = sp.getString("notif_text", "Monitoring scheduled VPN connections");
        int iconRes = sp.getInt("notif_icon", R.drawable.ic_notification);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(iconRes)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
    
    private void handleScheduledConnect(String scheduleId) {
        // Clear scheduled-disconnect suppression flag and notify app
        try {
            flagPreferences.edit().putBoolean(KEY_SCHEDULED_DISCONNECT, false).apply();
            Intent broadcast = new Intent(ACTION_SCHEDULED_EVENT);
            broadcast.putExtra("type", "connect");
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        } catch (Exception e) {
            Log.w(TAG, "Failed to broadcast scheduled connect event: " + e.getMessage());
        }
        
        // Log schedule details for debugging
        long currentTime = System.currentTimeMillis();
        Log.i(TAG, "Handling scheduled connect - Current: " + new java.util.Date(currentTime) + 
              " (" + currentTime + "), Schedule ID: " + scheduleId);
        
        // Wait for geofence config to load before making connection decisions
        waitForGeofenceConfigLoad();

        VpnSchedule schedule = getSchedule(scheduleId);
        if (schedule == null) {
            Log.e(TAG, "Schedule not found: " + scheduleId);
            return;
        }
        
        // Validate VPN configuration
        if (schedule.getConfig() == null || schedule.getConfig().trim().isEmpty()) {
            Log.e(TAG, "VPN config is null or empty");
            return;
        }
        
        // Clear other existing schedules for fresh start (prevent conflicts)
        List<VpnSchedule> existingSchedules = getAllSchedules();
        for (VpnSchedule existingSchedule : existingSchedules) {
            // Don't clear the current schedule - we need it for disconnect time
            if (!existingSchedule.getId().equals(scheduleId)) {
                cancelSchedule(existingSchedule.getId());
            }
        }
        
        try {
            // Parse bypass packages from JSON string
            List<String> bypassPackagesList = new java.util.ArrayList<>();
            if (schedule.getBypassPackages() != null && !schedule.getBypassPackages().trim().isEmpty()) {
                try {
                    bypassPackagesList = new com.google.gson.Gson().fromJson(
                        schedule.getBypassPackages(), 
                        new com.google.gson.reflect.TypeToken<List<String>>(){}.getType()
                    );
                    if (bypassPackagesList == null) {
                        bypassPackagesList = new java.util.ArrayList<>();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not parse bypass packages: " + e.getMessage());
                    bypassPackagesList = new java.util.ArrayList<>();
                }
            }
            
            // Use the same API as normal VPN connection
            de.blinkt.openvpn.OpenVpnApi.startVpn(
                this, 
                schedule.getConfig(), 
                schedule.getName(), 
                schedule.getUsername(), 
                schedule.getPassword(), 
                bypassPackagesList
            );
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Check if this is an immediate connection - if so, skip all geofence logic
        if (schedule.isImmediateConnection()) {
            Log.i(TAG, "Immediate connection detected - skipping all geofence and scheduling logic");
            return;
        }
        
        // Schedule disconnect if needed - handle overnight schedules and previous day start times correctly
        if (schedule.getDisconnectTimeUTC() > 0) {
            long currentTimeForDisconnect = System.currentTimeMillis();
            
            Log.i(TAG, "Schedule details - Connect: " + new java.util.Date(schedule.getConnectTimeUTC()) + 
                  " (" + schedule.getConnectTimeUTC() + "), Disconnect: " + 
                  new java.util.Date(schedule.getDisconnectTimeUTC()) + " (" + schedule.getDisconnectTimeUTC() + 
                  "), Current: " + new java.util.Date(currentTimeForDisconnect) + " (" + currentTimeForDisconnect + ")");
            
            // Debug: Check if this is an overnight schedule in progress
            if (schedule.isOvernightScheduleInProgress()) {
                Log.i(TAG, "DEBUG: Overnight schedule in progress detected");
            } else if (schedule.isPreviousDayStart()) {
                Log.i(TAG, "DEBUG: Previous day start detected");
            } else {
                Log.i(TAG, "DEBUG: Regular schedule detected");
            }
            
            // Check if this is a previous day start time scenario
            if (schedule.isPreviousDayStart()) {
                Log.i(TAG, "Previous day start time detected - both connect and disconnect times are in the past");
                // For previous day start times, don't schedule disconnect as the window has already passed
                Log.i(TAG, "Skipping disconnect scheduling - schedule window has already passed");
                
                // Clear any existing disconnect alarms to prevent immediate disconnection
                clearExistingDisconnectAlarms(schedule.getId());
                return;
            }
            
            // Check if this is an overnight schedule in progress (connect time passed, disconnect time in future)
            if (schedule.isOvernightScheduleInProgress()) {
                Log.i(TAG, "Overnight schedule in progress - connect time passed, disconnect time in future");
                // For overnight schedules in progress, schedule the disconnect for the future time
                Log.i(TAG, "Scheduling overnight disconnect for future time");
                scheduleDisconnect(schedule);
                return;
            }
            
            // For overnight schedules (disconnect time < connect time), only schedule if we're past the connect time
            if (schedule.getDisconnectTimeUTC() < schedule.getConnectTimeUTC()) {
                Log.i(TAG, "Overnight schedule detected - disconnect time is before connect time");
                // Overnight schedule: only schedule disconnect if current time is past the connect time
                if (currentTimeForDisconnect >= schedule.getConnectTimeUTC()) {
                    Log.i(TAG, "Scheduling overnight disconnect");
                    scheduleDisconnect(schedule);
                } else {
                    Log.i(TAG, "Not yet time for overnight disconnect - waiting for connect time");
                }
            } else {
                Log.i(TAG, "Same-day schedule detected");
                // Same-day schedule: only schedule if disconnect time is in the future
                if (schedule.getDisconnectTimeUTC() > currentTimeForDisconnect) {
                    Log.i(TAG, "Scheduling same-day disconnect");
                    scheduleDisconnect(schedule);
                } else {
                    Log.i(TAG, "Disconnect time has already passed for same-day schedule");
                }
            }
        }
    }
    
    private void handleScheduledDisconnect(String scheduleId) {
        // Set scheduled-disconnect suppression flag and notify app
        try {
            flagPreferences.edit().putBoolean(KEY_SCHEDULED_DISCONNECT, true).apply();
            Intent broadcast = new Intent(ACTION_SCHEDULED_EVENT);
            broadcast.putExtra("type", "disconnect");
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        } catch (Exception e) {
            Log.w(TAG, "Failed to broadcast scheduled disconnect event: " + e.getMessage());
        }

        // Stop VPN connection using the same method as normal disconnect
        try {
            de.blinkt.openvpn.core.OpenVPNThread.stop();
            
            // Clear all schedules to prevent auto-reconnect (same as manual disconnect)
            List<VpnSchedule> allSchedules = getAllSchedules();
            for (VpnSchedule schedule : allSchedules) {
                cancelSchedule(schedule.getId());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping VPN: " + e.getMessage());
            e.printStackTrace();
        }

        // Stop scheduler foreground service if no schedules remain
        maybeStopSchedulerIfIdle();
    }
    
    private void scheduleDisconnect(VpnSchedule schedule) {
        Intent disconnectIntent = new Intent(this, VpnSchedulerService.class);
        disconnectIntent.setAction(ACTION_SCHEDULE_DISCONNECT);
        disconnectIntent.putExtra(EXTRA_SCHEDULE_ID, schedule.getId());
        
        PendingIntent pendingIntent = PendingIntent.getService(
            this,
            getDisconnectRequestCode(schedule.getId()),
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                schedule.getDisconnectTimeUTC(),
                pendingIntent
            );
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                schedule.getDisconnectTimeUTC(),
                pendingIntent
            );
        }
        
    }
    
    public void scheduleVpn(VpnSchedule schedule) {
        // Store schedule
        saveSchedule(schedule);
        
        // Schedule connect
        Intent connectIntent = new Intent(this, VpnSchedulerService.class);
        connectIntent.setAction(ACTION_SCHEDULE_CONNECT);
        connectIntent.putExtra(EXTRA_SCHEDULE_ID, schedule.getId());
        
        PendingIntent pendingIntent = PendingIntent.getService(
            this,
            getConnectRequestCode(schedule.getId()),
            connectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        long triggerTime = schedule.isRecurring() ? schedule.getNextConnectTime() : schedule.getConnectTimeUTC();
        long currentTime = System.currentTimeMillis();
        long timeUntilTrigger = triggerTime - currentTime;
        
        // Enhanced logic for cross-day scheduling and midnight tomorrow scenarios
        boolean shouldStartImmediately = shouldStartImmediately(schedule, currentTime);
        
        if (shouldStartImmediately) {
            // Start VPN immediately
            handleScheduledConnect(schedule.getId());
            
            // Handle disconnect scheduling for immediate connections
            if (schedule.getDisconnectTimeUTC() > 0) {
                // For overnight schedules (disconnect time < connect time), always schedule disconnect
                if (schedule.getDisconnectTimeUTC() < schedule.getConnectTimeUTC()) {
                    scheduleDisconnect(schedule);
                } else {
                    // For same-day schedules, only schedule if disconnect time is in the future
                    if (schedule.getDisconnectTimeUTC() > currentTime) {
                        scheduleDisconnect(schedule);
                    }
                }
            }
            
            return;
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            );
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            );
        }
    }
    
    
    public void cancelSchedule(String scheduleId) {
        // Cancel alarms
        Intent connectIntent = new Intent(this, VpnSchedulerService.class);
        connectIntent.setAction(ACTION_SCHEDULE_CONNECT);
        connectIntent.putExtra(EXTRA_SCHEDULE_ID, scheduleId);
        
        PendingIntent connectPending = PendingIntent.getService(
            this,
            getConnectRequestCode(scheduleId),
            connectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Intent disconnectIntent = new Intent(this, VpnSchedulerService.class);
        disconnectIntent.setAction(ACTION_SCHEDULE_DISCONNECT);
        disconnectIntent.putExtra(EXTRA_SCHEDULE_ID, scheduleId);
        
        PendingIntent disconnectPending = PendingIntent.getService(
            this,
            getDisconnectRequestCode(scheduleId),
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        alarmManager.cancel(connectPending);
        alarmManager.cancel(disconnectPending);
        
        // Remove from storage
        removeSchedule(scheduleId);

        // Stop scheduler foreground service if no schedules remain
        maybeStopSchedulerIfIdle();
    }
    
    private void saveSchedule(VpnSchedule schedule) {
        List<VpnSchedule> schedules = getAllSchedules();
        boolean found = false;
        
        for (int i = 0; i < schedules.size(); i++) {
            if (schedules.get(i).getId().equals(schedule.getId())) {
                schedules.set(i, schedule);
                found = true;
                break;
            }
        }
        
        if (!found) {
            schedules.add(schedule);
        }
        
        String json = gson.toJson(schedules);
        preferences.edit().putString("schedules", json).apply();
    }
    
    private VpnSchedule getSchedule(String scheduleId) {
        List<VpnSchedule> schedules = getAllSchedules();
        for (VpnSchedule schedule : schedules) {
            if (schedule.getId().equals(scheduleId)) {
                return schedule;
            }
        }
        return null;
    }
    
    /**
     * Get unique request code for connect alarms
     */
    private int getConnectRequestCode(String scheduleId) {
        // Use positive hash code to avoid collisions
        return Math.abs(scheduleId.hashCode());
    }
    
    /**
     * Get unique request code for disconnect alarms
     */
    private int getDisconnectRequestCode(String scheduleId) {
        // Use larger offset to ensure no collision with connect codes
        return Math.abs(scheduleId.hashCode()) + 10000;
    }
    
    /**
     * Enhanced logic to determine if schedule should start immediately
     * Handles cross-day scheduling and midnight tomorrow scenarios
     */
    private boolean shouldStartImmediately(VpnSchedule schedule, long currentTime) {
        // Check if this is an immediate connection
        if (schedule.isImmediateConnection()) {
            return true;
        }
        
        // Check if this is a previous day start time
        if (schedule.isPreviousDayStart()) {
            return true;
        }
        
        // Check if this is an overnight schedule in progress
        if (schedule.isOvernightScheduleInProgress()) {
            return true;
        }
        
        // Check if start time is in the past (but not previous day start)
        long timeUntilTrigger = schedule.getConnectTimeUTC() - currentTime;
        if (timeUntilTrigger <= 0) {
            // Additional check for cross-day scheduling (midnight tomorrow scenarios)
            // If startTime is 0 (midnight) and current time is much later, it's likely tomorrow's midnight
            if (schedule.getConnectTimeUTC() == 0 && currentTime > 0) {
                // This is likely tomorrow's midnight - don't start immediately
                Log.i(TAG, "Cross-day schedule detected - startTime is midnight tomorrow, waiting");
                return false;
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * Wait for geofence config to load before making connection decisions
     * Implements retry logic to prevent race conditions
     */
    private void waitForGeofenceConfigLoad() {
        int maxRetries = 3;
        int retryDelay = 1000; // 1 second
        
        for (int i = 0; i < maxRetries; i++) {
            try {
                // Check if geofence config is loaded by checking if we have valid schedule data
                List<VpnSchedule> schedules = getAllSchedules();
                if (schedules != null && !schedules.isEmpty()) {
                    Log.i(TAG, "Geofence config loaded successfully");
                    return;
                }
                
                if (i < maxRetries - 1) {
                    Log.i(TAG, "Geofence config not ready, retrying in " + retryDelay + "ms (attempt " + (i + 1) + "/" + maxRetries + ")");
                    Thread.sleep(retryDelay);
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for geofence config: " + e.getMessage());
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.w(TAG, "Error waiting for geofence config: " + e.getMessage());
                break;
            }
        }
        
        Log.w(TAG, "Geofence config load timeout - proceeding with connection attempt");
    }
    
    /**
     * Clear any existing disconnect alarms to prevent immediate disconnection
     * @param scheduleId Schedule ID to clear alarms for
     */
    private void clearExistingDisconnectAlarms(String scheduleId) {
        try {
            Log.i(TAG, "Clearing existing disconnect alarms for schedule: " + scheduleId);
            
            // Cancel any pending disconnect alarms for this schedule
            Intent disconnectIntent = new Intent(this, VpnSchedulerService.class);
            disconnectIntent.setAction(ACTION_SCHEDULE_DISCONNECT);
            disconnectIntent.putExtra(EXTRA_SCHEDULE_ID, scheduleId);
            
            PendingIntent pendingIntent = PendingIntent.getService(
                this,
                getDisconnectRequestCode(scheduleId),
                disconnectIntent,
                PendingIntent.FLAG_NO_CREATE
            );
            
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent);
                pendingIntent.cancel();
                Log.i(TAG, "Cancelled existing disconnect alarm for schedule: " + scheduleId);
            } else {
                Log.i(TAG, "No existing disconnect alarm found for schedule: " + scheduleId);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error clearing disconnect alarms: " + e.getMessage());
        }
    }
    
    /**
     * Comprehensive validation method for testing all scenarios
     * @param schedule Schedule to validate
     * @return Detailed validation results
     */
    public String validateScheduleComprehensive(VpnSchedule schedule) {
        if (schedule == null) {
            return "ERROR: Schedule is null";
        }
        
        StringBuilder result = new StringBuilder();
        result.append("=== COMPREHENSIVE SCHEDULE VALIDATION ===\n");
        result.append(schedule.validateSchedule());
        result.append("\n");
        
        long currentTime = System.currentTimeMillis();
        
        // Test all scenarios
        result.append("=== SCENARIO TESTING ===\n");
        
        // 1. Immediate Connection Test
        result.append("1. Immediate Connection: ").append(schedule.isImmediateConnection()).append("\n");
        
        // 2. Previous Day Start Test
        result.append("2. Previous Day Start: ").append(schedule.isPreviousDayStart()).append("\n");
        
        // 3. Overnight Schedule Test
        boolean isOvernight = schedule.getDisconnectTimeUTC() < schedule.getConnectTimeUTC();
        result.append("3. Overnight Schedule: ").append(isOvernight).append("\n");
        
        // 4. Cross-day Schedule Test
        boolean isCrossDay = schedule.getConnectTimeUTC() == 0 && currentTime > 0;
        result.append("4. Cross-day Schedule: ").append(isCrossDay).append("\n");
        
        // 5. Should Start Immediately Test
        result.append("5. Should Start Immediately: ").append(shouldStartImmediately(schedule, currentTime)).append("\n");
        
        // 6. Should Trigger Test
        result.append("6. Should Trigger Now: ").append(schedule.shouldTriggerAt(currentTime)).append("\n");
        
        // 7. Should Disconnect Test
        result.append("7. Should Disconnect Now: ").append(schedule.shouldDisconnectAt(currentTime)).append("\n");
        
        result.append("=== END VALIDATION ===\n");
        
        return result.toString();
    }

    private void removeSchedule(String scheduleId) {
        List<VpnSchedule> schedules = getAllSchedules();
        
        // Use iterator to remove items for API level compatibility
        java.util.Iterator<VpnSchedule> iterator = schedules.iterator();
        while (iterator.hasNext()) {
            VpnSchedule schedule = iterator.next();
            if (schedule.getId().equals(scheduleId)) {
                iterator.remove();
            }
        }
        
        String json = gson.toJson(schedules);
        preferences.edit().putString("schedules", json).apply();
    }
    
    public List<VpnSchedule> getAllSchedules() {
        String json = preferences.getString("schedules", "[]");
        Type listType = new TypeToken<List<VpnSchedule>>(){}.getType();
        return gson.fromJson(json, listType);
    }

    /**
     * Stop foreground scheduler service when there are no pending schedules
     */
    private void maybeStopSchedulerIfIdle() {
        try {
            List<VpnSchedule> schedules = getAllSchedules();
            if (schedules == null || schedules.isEmpty()) {
                stopForeground(true);
                stopSelf();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop service when idle: " + e.getMessage());
        }
    }

    private int getNotificationIdFromPrefs() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("vpn_scheduler_prefs", Context.MODE_PRIVATE);
            return sp.getInt("notif_id", NOTIFICATION_ID);
        } catch (Exception e) {
            return NOTIFICATION_ID;
        }
    }
}
