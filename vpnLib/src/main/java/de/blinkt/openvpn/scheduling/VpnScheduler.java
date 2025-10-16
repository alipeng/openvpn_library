package de.blinkt.openvpn.scheduling;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Calendar;
import java.util.List;

/**
 * Main interface for VPN scheduling functionality
 */
public class VpnScheduler {
    private static final String TAG = "VpnScheduler";
    
    private Context context;
    private VpnSchedulerService schedulerService;
    private static final String PREFS = "vpn_scheduler_prefs";
    private static final String KEY_NOTIF_TITLE = "notif_title";
    private static final String KEY_NOTIF_TEXT = "notif_text";
    private static final String KEY_NOTIF_ICON = "notif_icon";
    private static final String KEY_NOTIF_ID = "notif_id";
    
    public VpnScheduler(Context context) {
        this.context = context;
    }
    
    /**
     * Schedule a VPN connection at a specific UTC time
     * @param config OpenVPN configuration string
     * @param name Profile name
     * @param username Authentication username
     * @param password Authentication password
     * @param connectTimeUTC Connect time in UTC milliseconds
     * @param disconnectTimeUTC Disconnect time in UTC milliseconds (0 for manual disconnect)
     * @param bypassPackages List of package names to bypass VPN
     * @return Schedule ID
     */
    public String scheduleVpn(String config, String name, String username, String password,
                              long connectTimeUTC, long disconnectTimeUTC, List<String> bypassPackages) {
        
        VpnSchedule schedule = new VpnSchedule(config, name, username, password, connectTimeUTC, disconnectTimeUTC);
        
        if (bypassPackages != null && !bypassPackages.isEmpty()) {
            schedule.setBypassPackages(new com.google.gson.Gson().toJson(bypassPackages));
        }
        
        startSchedulerService();
        scheduleVpnInternal(schedule);
        
        return schedule.getId();
    }
    
    
    /**
     * Cancel a scheduled VPN
     * @param scheduleId Schedule ID to cancel
     */
    public void cancelSchedule(String scheduleId) {
        startSchedulerService();
        cancelScheduleInternal(scheduleId);
    }
    
    /**
     * Cancel schedule directly without sending intent (for internal use)
     * @param scheduleId Schedule ID to cancel
     */
    public void cancelScheduleDirect(String scheduleId) {
        // Cancel alarms directly
        android.app.AlarmManager alarmManager = (android.app.AlarmManager) context.getSystemService(android.content.Context.ALARM_SERVICE);
        
        android.content.Intent connectIntent = new android.content.Intent(context, VpnSchedulerService.class);
        connectIntent.setAction("de.blinkt.openvpn.SCHEDULE_CONNECT");
        connectIntent.putExtra("schedule_id", scheduleId);
        
        android.app.PendingIntent connectPending = android.app.PendingIntent.getService(
            context,
            getConnectRequestCode(scheduleId),
            connectIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
        );
        
        android.content.Intent disconnectIntent = new android.content.Intent(context, VpnSchedulerService.class);
        disconnectIntent.setAction("de.blinkt.openvpn.SCHEDULE_DISCONNECT");
        disconnectIntent.putExtra("schedule_id", scheduleId);
        
        android.app.PendingIntent disconnectPending = android.app.PendingIntent.getService(
            context,
            getDisconnectRequestCode(scheduleId),
            disconnectIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
        );
        
        alarmManager.cancel(connectPending);
        alarmManager.cancel(disconnectPending);
        
        // Remove from storage directly
        removeScheduleDirect(scheduleId);
    }
    
    private void removeScheduleDirect(String scheduleId) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("vpn_schedules", android.content.Context.MODE_PRIVATE);
        String json = prefs.getString("schedules", "[]");
        
        com.google.gson.Gson gson = new com.google.gson.Gson();
        java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<java.util.List<VpnSchedule>>(){}.getType();
        java.util.List<VpnSchedule> schedules = gson.fromJson(json, listType);
        
        if (schedules == null) {
            schedules = new java.util.ArrayList<>();
        }
        
        // Remove the schedule
        java.util.Iterator<VpnSchedule> iterator = schedules.iterator();
        while (iterator.hasNext()) {
            VpnSchedule schedule = iterator.next();
            if (schedule.getId().equals(scheduleId)) {
                iterator.remove();
                break;
            }
        }
        
        // Save back to preferences
        String updatedJson = gson.toJson(schedules);
        prefs.edit().putString("schedules", updatedJson).apply();
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
     * Get all scheduled VPNs
     * @return List of all schedules
     */
    public List<VpnSchedule> getAllSchedules() {
        startSchedulerService();
        return getAllSchedulesInternal();
    }
    
    /**
     * Clear all scheduled VPNs (for immediate connections)
     */
    public void clearAllSchedules() {
        Log.i(TAG, "Clearing all scheduled VPNs for immediate connection");
        android.content.SharedPreferences prefs = context.getSharedPreferences("vpn_schedules", android.content.Context.MODE_PRIVATE);
        prefs.edit().putString("schedules", "[]").apply();
        
        // Cancel all alarms
        android.app.AlarmManager alarmManager = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            // Cancel all pending intents for this app
            android.content.Intent connectIntent = new android.content.Intent(context, VpnSchedulerService.class);
            android.content.Intent disconnectIntent = new android.content.Intent(context, VpnSchedulerService.class);
            
            android.app.PendingIntent connectPendingIntent = android.app.PendingIntent.getService(
                context, 0, connectIntent, android.app.PendingIntent.FLAG_NO_CREATE);
            android.app.PendingIntent disconnectPendingIntent = android.app.PendingIntent.getService(
                context, 0, disconnectIntent, android.app.PendingIntent.FLAG_NO_CREATE);
                
            if (connectPendingIntent != null) {
                alarmManager.cancel(connectPendingIntent);
                connectPendingIntent.cancel();
            }
            if (disconnectPendingIntent != null) {
                alarmManager.cancel(disconnectPendingIntent);
                disconnectPendingIntent.cancel();
            }
        }
    }
    
    /**
     * Check if there's an active schedule running (between start and end time)
     * This helps the app avoid starting VPN again when a schedule is already in progress
     * @return true if there's an active schedule, false otherwise
     */
    public boolean hasActiveSchedule() {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("vpn_schedules", android.content.Context.MODE_PRIVATE);
            String json = prefs.getString("schedules", "[]");
            
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<java.util.List<VpnSchedule>>(){}.getType();
            java.util.List<VpnSchedule> schedules = gson.fromJson(json, listType);
            
            if (schedules == null || schedules.isEmpty()) {
                return false;
            }
            
            long currentTime = System.currentTimeMillis();
            
            for (VpnSchedule schedule : schedules) {
                if (schedule.isActive() && schedule.isWithinGeofenceHours()) {
                    Log.i(TAG, "Active schedule found: " + schedule.getName() + 
                          " (Connect: " + new java.util.Date(schedule.getConnectTimeUTC()) + 
                          ", Disconnect: " + new java.util.Date(schedule.getDisconnectTimeUTC()) + 
                          ", Current: " + new java.util.Date(currentTime) + ")");
                    return true;
                }
            }
            
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking active schedules: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get details of active schedule (if any)
     * @return VpnSchedule object if there's an active schedule, null otherwise
     */
    public VpnSchedule getActiveSchedule() {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("vpn_schedules", android.content.Context.MODE_PRIVATE);
            String json = prefs.getString("schedules", "[]");
            
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<java.util.List<VpnSchedule>>(){}.getType();
            java.util.List<VpnSchedule> schedules = gson.fromJson(json, listType);
            
            if (schedules == null || schedules.isEmpty()) {
                return null;
            }
            
            for (VpnSchedule schedule : schedules) {
                if (schedule.isActive() && schedule.isWithinGeofenceHours()) {
                    return schedule;
                }
            }
            
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting active schedule: " + e.getMessage());
            return null;
        }
    }
    
    private void startSchedulerService() {
        Intent serviceIntent = new Intent(context, VpnSchedulerService.class);
        context.startService(serviceIntent);
    }
    
    private void scheduleVpnInternal(VpnSchedule schedule) {
        // Start the service and pass the schedule
        Intent serviceIntent = new Intent(context, VpnSchedulerService.class);
        serviceIntent.putExtra("action", "schedule");
        serviceIntent.putExtra("schedule", schedule);
        context.startService(serviceIntent);
    }
    
    private void cancelScheduleInternal(String scheduleId) {
        Intent serviceIntent = new Intent(context, VpnSchedulerService.class);
        serviceIntent.putExtra("action", "cancel");
        serviceIntent.putExtra("scheduleId", scheduleId);
        context.startService(serviceIntent);
    }
    
    private List<VpnSchedule> getAllSchedulesInternal() {
        // For now, return empty list - this would need proper service binding
        return new java.util.ArrayList<>();
    }
    
    private void updateScheduleInternal(VpnSchedule schedule) {
        Intent serviceIntent = new Intent(context, VpnSchedulerService.class);
        serviceIntent.putExtra("action", "update");
        serviceIntent.putExtra("schedule", schedule);
        context.startService(serviceIntent);
    }

    /**
     * Configure notification appearance for the scheduler foreground service.
     * Call from app before scheduling.
     * @param notificationId Notification ID
     * @param title Notification title
     * @param text Notification text/description
     * @param smallIconResId Resource id for small icon (e.g., R.drawable.ic_notification)
     */
    public void configureNotification(int notificationId, String title, String text, int smallIconResId) {
        android.content.SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit()
          .putString(KEY_NOTIF_TITLE, title)
          .putString(KEY_NOTIF_TEXT, text)
          .putInt(KEY_NOTIF_ICON, smallIconResId)
          .putInt(KEY_NOTIF_ID, notificationId)
          .apply();
        // Nudge service to refresh if running
        startSchedulerService();
    }
    
    /**
     * Trigger the scheduler service to check if it should stop (when no schedules remain)
     */
    public void triggerIdleCheck() {
        Intent serviceIntent = new Intent(context, VpnSchedulerService.class);
        serviceIntent.putExtra("action", "check_idle");
        context.startService(serviceIntent);
    }
}
