package de.blinkt.openvpn;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;
import android.os.RemoteException; 
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.OpenVPNThread;
import de.blinkt.openvpn.core.VpnStatus;
import de.blinkt.openvpn.scheduling.VpnScheduler;
import de.blinkt.openvpn.scheduling.VpnSchedule;

public class VPNHelper extends Activity {
    public Activity activity;
    public static OnVPNStatusChangeListener listener;
    private static String config;
    private static boolean vpnStart;
    private static Intent profileIntent;
    private static String username;
    private static String password;
    private static String name;
    private static List<String> bypassPackages;

    public JSONObject status = new JSONObject();
    private VpnScheduler vpnScheduler;

    public boolean isConnected(){
        return vpnStart;
    }
    
    /**
     * Set whether to hide all VPN notifications
     * @param hideNotification true to hide all notifications, false to show them
     */
    public void setHideAllNotifications(boolean hideNotification) {
        de.blinkt.openvpn.core.OpenVPNService.setHideAllNotifications(hideNotification);
    }
    
    /**
     * Hide all VPN notifications by default
     */
    public void hideAllNotifications() {
        de.blinkt.openvpn.core.OpenVPNService.setHideAllNotifications(true);
    }
    
    /**
     * Show all VPN notifications
     */
    public void showAllNotifications() {
        de.blinkt.openvpn.core.OpenVPNService.setHideAllNotifications(false);
    }
    
    /**
     * Disconnect button is now hidden from all notifications by default
     * This method is kept for compatibility but doesn't need to be called
     */
    public void setHideDisconnectButton(boolean hideDisconnectButton) {
        // Disconnect button is hidden by default in all notifications
        // No action needed as it's handled in the core service
    }

    public VPNHelper(Activity activity) { 
        this.activity = activity;
        VPNHelper.vpnStart = false;
        VpnStatus.initLogCache(activity.getCacheDir());
        this.vpnScheduler = new VpnScheduler(activity);
    }

    public void setOnVPNStatusChangeListener(OnVPNStatusChangeListener listener) {
        VPNHelper.listener = listener;
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver, new IntentFilter("connectionState"));
    }

    public void startVPN() {
         if (!vpnStart) {
            connect();
        }
    }


    public void startVPN(String config, String username, String password, String name, List<String> bypass) {
        startVPN(config, username, password, name, bypass, 0L, 0L, null, null, null, 0, true); // Default to immediate
    }
    
    /**
     * Check if there's an active schedule running (between start and end time)
     * This helps the app avoid starting VPN again when a schedule is already in progress
     * @return true if there's an active schedule, false otherwise
     */
    public boolean hasActiveSchedule() {
        if (vpnScheduler == null) {
            return false;
        }
        return vpnScheduler.hasActiveSchedule();
    }
    
    /**
     * Get details of active schedule (if any)
     * @return VpnSchedule object if there's an active schedule, null otherwise
     */
    public VpnSchedule getActiveSchedule() {
        if (vpnScheduler == null) {
            return null;
        }
        return vpnScheduler.getActiveSchedule();
    }
    
    // Overload for app compatibility - 7 parameters (long, long)
    public String startVPN(String config, String username, String password, String name, List<String> bypassPackages, long startTimeUTC, long endTimeUTC) {
        return startVPN(config, username, password, name, bypassPackages, startTimeUTC, endTimeUTC, null, null, null, 0, false);
    }
    
    // Overload for app compatibility - 7 parameters (int, int) 
    public String startVPN(String config, String username, String password, String name, List<String> bypassPackages, int startTimeUTC, int endTimeUTC) {
        return startVPN(config, username, password, name, bypassPackages, (long)startTimeUTC, (long)endTimeUTC, null, null, null, 0, false);
    }
    
    // Overload for backward compatibility - without isImmediateConnection flag
    public String startVPN(String config, String username, String password, String name, List<String> bypassPackages, long startTimeUTC, long endTimeUTC, String notificationTitle, String notificationText, String notificationIcon, int notificationId) {
        return startVPN(config, username, password, name, bypassPackages, startTimeUTC, endTimeUTC, notificationTitle, notificationText, notificationIcon, notificationId, false);
    }

    /**
     * Single function to start VPN - immediate or scheduled
     * @param config OpenVPN configuration string
     * @param username Authentication username
     * @param password Authentication password
     * @param name Profile name
     * @param bypassPackages List of package names to bypass VPN
     * @param startTimeUTC Start time in UTC milliseconds (0 for immediate connection)
     * @param endTimeUTC End time in UTC milliseconds (0 for manual disconnect)
     * @param isImmediateConnection If true, bypass all scheduling logic and connect immediately
     * @return Schedule ID if scheduled, null if immediate
     */
    public String startVPN(String config, String username, String password, String name, List<String> bypassPackages, long startTimeUTC, long endTimeUTC, String notificationTitle, String notificationText, String notificationIcon, int notificationId, boolean isImmediateConnection) {
        
        VPNHelper.config = config;
        VPNHelper.profileIntent = VpnService.prepare(activity);
        VPNHelper.username = username;
        VPNHelper.password = password;
        VPNHelper.name = name;
        VPNHelper.bypassPackages = bypassPackages;

        // Configure scheduler notification (use provided values or defaults)
        if (vpnScheduler != null) {
            String title = (notificationTitle != null && !notificationTitle.isEmpty()) ? notificationTitle : "VPN Scheduler";
            String text = (notificationText != null && !notificationText.isEmpty()) ? notificationText : "Monitoring scheduled VPN connections";
            int iconRes = R.drawable.ic_notification; // Default icon
            int notifId = (notificationId > 0) ? notificationId : 1001; // Default ID
            
            // Try to parse icon resource if provided
            if (notificationIcon != null && !notificationIcon.isEmpty()) {
                try {
                    iconRes = activity.getResources().getIdentifier(notificationIcon, "drawable", activity.getPackageName());
                    if (iconRes == 0) { 
                        iconRes = R.drawable.ic_notification;
                    }
                } catch (Exception e) { 
                    iconRes = R.drawable.ic_notification;
                }
            }
            
            vpnScheduler.configureNotification(notifId, title, text, iconRes); 
        }
        
        // Enhanced immediate connection detection
        boolean shouldConnectImmediately = isImmediateConnection || 
                                          startTimeUTC <= 0 || 
                                          (startTimeUTC > 0 && endTimeUTC <= 0); // Missing end time = immediate
        
        if (shouldConnectImmediately) { 
            Log.i("VPNHelper", "Starting immediate VPN connection - bypassing all scheduling logic");
            
            // Clear any stale geofence configs for immediate connections
            if (vpnScheduler != null) {
                try {
                    vpnScheduler.clearAllSchedules();
                    Log.i("VPNHelper", "Cleared stale geofence configs for immediate connection");
                } catch (Exception e) {
                    Log.w("VPNHelper", "clearAllSchedules failed during immediate connection: " + e.getMessage());
                }
            }
            
            if (profileIntent != null) { 
                activity.startActivityForResult(VPNHelper.profileIntent, 1);
            } else { 
                startVPN();
            }
            return "immediate_connection"; // Return non-null token to avoid platform null errors
        }
        
        // Debug logging for UTC timestamps
        Log.i("VPNHelper", "UTC Timestamps - Connect: " + new java.util.Date(startTimeUTC) + 
              " (" + startTimeUTC + "), Disconnect: " + new java.util.Date(endTimeUTC) + 
              " (" + endTimeUTC + "), Current: " + new java.util.Date(System.currentTimeMillis()));
        
        // Check if this is an overnight schedule where end time is before start time
        // This happens when app sends midnight (00:00) as end time for overnight schedules
        long correctedEndTimeUTC = endTimeUTC;
        if (endTimeUTC > 0 && endTimeUTC < startTimeUTC) {
            // This is likely an overnight schedule where end time (midnight) is before start time
            // Move end time to next day
            java.util.Calendar endTime = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            endTime.setTimeInMillis(endTimeUTC);
            endTime.add(java.util.Calendar.DAY_OF_MONTH, 1);
            correctedEndTimeUTC = endTime.getTimeInMillis();
            
            Log.i("VPNHelper", "Overnight schedule detected - end time moved to next day: " + 
                  new java.util.Date(correctedEndTimeUTC) + " (" + correctedEndTimeUTC + ")");
        }
        
        // Schedule VPN with corrected end time
        String scheduleId = scheduleVpn(config, name, username, password, startTimeUTC, correctedEndTimeUTC, bypassPackages);
        return scheduleId;
    }


    /**
     * Start VPN with optional scheduling (today's time)
     * @param config OpenVPN configuration string
     * @param username Authentication username
     * @param password Authentication password
     * @param name Profile name
     * @param bypassPackages List of package names to bypass VPN
     * @param connectHour Connect hour (0-23, -1 for immediate)
     * @param connectMinute Connect minute (0-59)
     * @param disconnectHour Disconnect hour (0-23, -1 for manual disconnect)
     * @param disconnectMinute Disconnect minute (0-59)
     * @return Schedule ID if scheduled, null if immediate connection
     */
    public String startVPN(String config, String username, String password, String name, 
                          List<String> bypassPackages, int connectHour, int connectMinute, 
                          int disconnectHour, int disconnectMinute) {
        
        
        // If no scheduling time provided, use original immediate connection
        if (connectHour < 0) {
            startVPN(config, username, password, name, bypassPackages);
            return null;
        }
        
        // Handle hour/minute parameters like iOS - convert to UTC timestamps
        // Force UTC timezone for consistent comparison (like iOS)
        java.util.TimeZone utcTimeZone = java.util.TimeZone.getTimeZone("UTC");
        java.util.Calendar connectTime = java.util.Calendar.getInstance(utcTimeZone);
        connectTime.set(java.util.Calendar.HOUR_OF_DAY, connectHour);
        connectTime.set(java.util.Calendar.MINUTE, connectMinute);
        connectTime.set(java.util.Calendar.SECOND, 0);
        connectTime.set(java.util.Calendar.MILLISECOND, 0);
        
        java.util.Calendar disconnectTime = java.util.Calendar.getInstance(utcTimeZone);
        disconnectTime.set(java.util.Calendar.HOUR_OF_DAY, disconnectHour);
        disconnectTime.set(java.util.Calendar.MINUTE, disconnectMinute);
        disconnectTime.set(java.util.Calendar.SECOND, 0);
        disconnectTime.set(java.util.Calendar.MILLISECOND, 0);
        
        // Handle overnight schedules like iOS: if disconnect time is before or equal to connect time, it's next day
        if (disconnectTime.getTimeInMillis() <= connectTime.getTimeInMillis()) {
            // For overnight schedules (e.g., 5:00 AM to midnight), disconnect is next day
            disconnectTime.add(java.util.Calendar.DAY_OF_MONTH, 1);
            Log.i("VPNHelper", "Overnight schedule detected - disconnect time moved to next day");
        }
        
        // Debug logging for timezone conversion
        Log.i("VPNHelper", "UTC Time Conversion - Connect: " + new java.util.Date(connectTime.getTimeInMillis()) + 
              " (" + connectTime.getTimeInMillis() + "), Disconnect: " + 
              new java.util.Date(disconnectTime.getTimeInMillis()) + " (" + disconnectTime.getTimeInMillis() + ")");
        
        // Schedule VPN for today
        String scheduleId = scheduleVpn(config, name, username, password, 
                         connectTime.getTimeInMillis(), 
                         disconnectTime.getTimeInMillis(), 
                         bypassPackages);
        return scheduleId;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.launchvpn);
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver, new IntentFilter("connectionState"));
        startVPN();
    }

    public void stopVPN() {
        try {
            OpenVPNThread.stop();
        } catch (Exception e) {
            Log.w("VPN", "Error stopping VPN (may not be running): " + e.getMessage());
            // Continue with cleanup even if VPN stop fails
        }
        
        // Set manual-disconnect suppression flag and notify app (same as scheduled disconnect)
        try {
            android.content.SharedPreferences flagPrefs = activity.getSharedPreferences("vpn_scheduler_flags", android.content.Context.MODE_PRIVATE);
            flagPrefs.edit().putBoolean("scheduled_disconnect", true).apply();
            
            // Broadcast manual disconnect event to app
            android.content.Intent broadcast = new android.content.Intent("de.blinkt.openvpn.SCHEDULED_EVENT");
            broadcast.putExtra("type", "disconnect");
            LocalBroadcastManager.getInstance(activity).sendBroadcast(broadcast);
        } catch (Exception e) {
            Log.w("VPN", "Failed to broadcast manual disconnect event: " + e.getMessage());
        }
        
        // Clear all previous schedules when disconnecting
        if (vpnScheduler != null) {
            List<VpnSchedule> schedules = vpnScheduler.getAllSchedules();
            for (VpnSchedule schedule : schedules) {
                 vpnScheduler.cancelScheduleDirect(schedule.getId());
            }
            
            // Trigger scheduler service to check if it should stop
            vpnScheduler.triggerIdleCheck();
        }
    }


    // ========== INTERNAL SCHEDULING METHODS ==========

    /**
     * Internal method to schedule VPN
     */
    private String scheduleVpn(String config, String name, String username, String password,
                             long startTimeUTC, long endTimeUTC, List<String> bypassPackages) {
        return vpnScheduler.scheduleVpn(config, name, username, password, startTimeUTC, endTimeUTC, bypassPackages);
    }

    private void connect() {
        try {
            OpenVpnApi.startVpn(activity, config,name, username, password, bypassPackages);
            vpnStart = true;
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void setStage(String stage) {
        String output = stage;
        switch (stage.toUpperCase()) {
            case "CONNECTED":
                output = "connected";
                vpnStart = true;
                break;
            case "DISCONNECTED":
                output = "disconnected";
                vpnStart = false;
                OpenVPNService.setDefaultStatus();
                break;
            case "WAIT":
                output = "wait_connection";
                break;
            case "AUTH":
                output = "authenticating";
                break;
            case "RECONNECTING":
                output = "reconnect";
                break;
            case "NONETWORK":
                output = "no_connection";
                break;
            case "CONNECTING":
                output = "connecting";
                break;
            case "PREPARE":
                output = "prepare";
                break;
            case "DENIED":
                output = "denied";
                break;
            case "ERROR":
                output = "error";
                break;
        }
        if (listener != null) listener.onVPNStatusChanged(output);
    }


    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            try {
                if (intent.getStringExtra("state") != null) {
                    setStage(intent.getStringExtra("state"));
                }
                String duration = intent.getStringExtra("duration");
                String lastPacketReceive = intent.getStringExtra("lastPacketReceive");
                String byteIn = intent.getStringExtra("byteIn");
                String byteOut = intent.getStringExtra("byteOut");

                if (duration == null) duration = "00:00:00";
                if (lastPacketReceive == null) lastPacketReceive = "0";
                if (byteIn == null) byteIn = " ";
                if (byteOut == null) byteOut = " ";
                JSONObject jsonObject = new JSONObject();

                try {
                    jsonObject.put("connected_on", duration);
                    jsonObject.put("last_packet_receive", lastPacketReceive);
                    jsonObject.put("byte_in", byteIn);
                    jsonObject.put("byte_out", byteOut);

                    status = jsonObject;
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                VPNHelper.listener.onConnectionStatusChanged(duration, lastPacketReceive, byteIn, byteOut);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    };

    @Override
    public void onDetachedFromWindow() {
        LocalBroadcastManager.getInstance(activity).unregisterReceiver(broadcastReceiver);
        super.onDetachedFromWindow();
    }

    @Override
    public void onAttachedToWindow() {
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver, new IntentFilter("connectionState"));
        super.onAttachedToWindow();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1) {
            if (resultCode == RESULT_OK) {
                startVPN();
            } else {
                VPNHelper.listener.onVPNStatusChanged("denied");
            }
        }
    }
}