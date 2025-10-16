package de.blinkt.openvpn.scheduling;

import java.io.Serializable;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

/**
 * Data model for VPN scheduling
 */
public class VpnSchedule implements Serializable {
    private String id;
    private String config;
    private String name;
    private String username;
    private String password;
    private long connectTimeUTC;
    private long disconnectTimeUTC;
    private boolean isActive;
    private boolean isRecurring;
    private int recurringDays; // Bitmask for days of week (1=Sunday, 2=Monday, etc.)
    private String bypassPackages; // JSON string of package names
    
    public VpnSchedule() {
        this.id = UUID.randomUUID().toString();
        this.isActive = true;
        this.isRecurring = false;
        this.recurringDays = 0;
    }
    
    public VpnSchedule(String config, String name, String username, String password, 
                      long connectTimeUTC, long disconnectTimeUTC) {
        this();
        this.config = config;
        this.name = name;
        this.username = username;
        this.password = password;
        this.connectTimeUTC = connectTimeUTC;
        this.disconnectTimeUTC = disconnectTimeUTC;
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    
    public long getConnectTimeUTC() { return connectTimeUTC; }
    public void setConnectTimeUTC(long connectTimeUTC) { this.connectTimeUTC = connectTimeUTC; }
    
    public long getDisconnectTimeUTC() { return disconnectTimeUTC; }
    public void setDisconnectTimeUTC(long disconnectTimeUTC) { this.disconnectTimeUTC = disconnectTimeUTC; }
    
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    
    public boolean isRecurring() { return isRecurring; }
    public void setRecurring(boolean recurring) { isRecurring = recurring; }
    
    public int getRecurringDays() { return recurringDays; }
    public void setRecurringDays(int recurringDays) { this.recurringDays = recurringDays; }
    
    public String getBypassPackages() { return bypassPackages; }
    public void setBypassPackages(String bypassPackages) { this.bypassPackages = bypassPackages; }
    
    /**
     * Get the next connect time for recurring schedules
     * Since app always sends UTC time, we work with UTC timestamps
     */
    public long getNextConnectTime() {
        if (!isRecurring) {
            return connectTimeUTC;
        }
        
        // For recurring schedules, calculate next occurrence
        long currentTime = System.currentTimeMillis();
        
        // If connect time is in the future, use it
        if (connectTimeUTC > currentTime) {
            return connectTimeUTC;
        }
        
        // Calculate next occurrence based on recurring days
        Calendar connect = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        connect.setTimeInMillis(connectTimeUTC);
        
        Calendar now = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        now.setTimeInMillis(currentTime);
        
        // Find next occurrence within the next 7 days
        for (int i = 0; i < 7; i++) {
            Calendar next = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            next.setTimeInMillis(connectTimeUTC);
            next.add(Calendar.DAY_OF_YEAR, i);
            
            int dayOfWeek = next.get(Calendar.DAY_OF_WEEK);
            int dayMask = 1 << (dayOfWeek - 1);
            
            if ((recurringDays & dayMask) != 0 && next.getTimeInMillis() > currentTime) {
                return next.getTimeInMillis();
            }
        }
        
        // If no valid day found in next 7 days, return original time
        return connectTimeUTC;
    }
    
    /**
     * Check if schedule should trigger at given time
     * Enhanced with overnight schedule logic using OR logic for midnight crossing
     */
    public boolean shouldTriggerAt(long currentTimeUTC) {
        if (!isActive) return false;
        
        // Since both currentTimeUTC and connectTimeUTC are in UTC, no timezone conversion needed
        if (isRecurring) {
            // For recurring schedules, check if current day matches recurring days
            // Convert UTC time to day of week for recurring logic
            Calendar current = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            current.setTimeInMillis(currentTimeUTC);
            
            Calendar connect = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            connect.setTimeInMillis(connectTimeUTC);
            
            // Check if current day matches recurring days
            int currentDayOfWeek = current.get(Calendar.DAY_OF_WEEK);
            int dayMask = 1 << (currentDayOfWeek - 1);
            if ((recurringDays & dayMask) == 0) return false;
            
            // Check if current time is within connect time window (within 1 minute)
            long timeDiff = Math.abs(currentTimeUTC - connectTimeUTC);
            return timeDiff <= 60000; // 1 minute tolerance
        } else {
            // One-time schedule - handle overnight schedules with OR logic
            return shouldTriggerAtOneTime(currentTimeUTC);
        }
    }
    
    /**
     * Check if one-time schedule should trigger, handling overnight schedules like iOS
     * Uses OR logic: currentTime >= startTime || currentTime <= endTime (for overnight)
     */
    private boolean shouldTriggerAtOneTime(long currentTimeUTC) {
        // Convert to minutes for easier comparison (like iOS)
        java.util.Calendar current = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        current.setTimeInMillis(currentTimeUTC);
        int currentHour = current.get(java.util.Calendar.HOUR_OF_DAY);
        int currentMinute = current.get(java.util.Calendar.MINUTE);
        int currentTimeMinutes = currentHour * 60 + currentMinute;
        
        java.util.Calendar connect = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        connect.setTimeInMillis(connectTimeUTC);
        int connectHour = connect.get(java.util.Calendar.HOUR_OF_DAY);
        int connectMinute = connect.get(java.util.Calendar.MINUTE);
        int startTimeMinutes = connectHour * 60 + connectMinute;
        
        java.util.Calendar disconnect = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        disconnect.setTimeInMillis(disconnectTimeUTC);
        int disconnectHour = disconnect.get(java.util.Calendar.HOUR_OF_DAY);
        int disconnectMinute = disconnect.get(java.util.Calendar.MINUTE);
        int endTimeMinutes = disconnectHour * 60 + disconnectMinute;
        
        // Handle overnight schedules like iOS
        if (startTimeMinutes < endTimeMinutes) {
            // Same day schedule: [start, end]
            return currentTimeMinutes >= startTimeMinutes && currentTimeMinutes <= endTimeMinutes;
        } else {
            // Overnight schedule: [start, 1440) ∪ [0, end]
            return currentTimeMinutes >= startTimeMinutes || currentTimeMinutes <= endTimeMinutes;
        }
    }
    
    /**
     * Check if schedule should disconnect at given time like iOS
     * Enhanced to only check when VPN is actually connected and handle overnight schedules
     */
    public boolean shouldDisconnectAt(long currentTimeUTC) {
        if (!isActive) return false;
        
        // Only check end time when VPN is actually connected
        // This prevents premature disconnections during connection establishment
        
        // Convert to minutes for easier comparison (like iOS)
        java.util.Calendar current = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        current.setTimeInMillis(currentTimeUTC);
        int currentHour = current.get(java.util.Calendar.HOUR_OF_DAY);
        int currentMinute = current.get(java.util.Calendar.MINUTE);
        int currentTimeMinutes = currentHour * 60 + currentMinute;
        
        java.util.Calendar connect = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        connect.setTimeInMillis(connectTimeUTC);
        int connectHour = connect.get(java.util.Calendar.HOUR_OF_DAY);
        int connectMinute = connect.get(java.util.Calendar.MINUTE);
        int startTimeMinutes = connectHour * 60 + connectMinute;
        
        java.util.Calendar disconnect = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        disconnect.setTimeInMillis(disconnectTimeUTC);
        int disconnectHour = disconnect.get(java.util.Calendar.HOUR_OF_DAY);
        int disconnectMinute = disconnect.get(java.util.Calendar.MINUTE);
        int endTimeMinutes = disconnectHour * 60 + disconnectMinute;
        
        // Handle overnight schedules like iOS
        if (startTimeMinutes < endTimeMinutes) {
            // Same day schedule: end time is exclusive
            return currentTimeMinutes >= endTimeMinutes;
        } else {
            // Overnight schedule: end time is the first minute outside the window
            return currentTimeMinutes >= endTimeMinutes && currentTimeMinutes < startTimeMinutes;
        }
    }
    
    /**
     * Check if this is an immediate connection (no scheduling)
     * @return true if this is an immediate connection that should bypass geofence logic
     */
    public boolean isImmediateConnection() {
        // If connect time is 0 or in the past, and no disconnect time set, it's immediate
        long currentTime = System.currentTimeMillis();
        return (connectTimeUTC <= 0 || connectTimeUTC <= currentTime) && disconnectTimeUTC <= 0;
    }
    
    /**
     * Check if this is a previous day start time scenario
     * @return true if both connect and disconnect times are in the past
     */
    public boolean isPreviousDayStart() {
        long currentTime = System.currentTimeMillis();
        return connectTimeUTC < currentTime && disconnectTimeUTC < currentTime;
    }
    
    /**
     * Check if this is an overnight schedule that should start immediately
     * @return true if connect time is in the past but disconnect time is in the future (overnight schedule)
     */
    public boolean isOvernightScheduleInProgress() {
        long currentTime = System.currentTimeMillis();
        return connectTimeUTC < currentTime && disconnectTimeUTC > currentTime;
    }
    
    /**
     * Check if we're within geofence hours like iOS
     * @return true if current time is within the schedule window
     */
    public boolean isWithinGeofenceHours() {
        long currentTime = System.currentTimeMillis();
        
        // Convert to minutes for easier comparison (like iOS)
        java.util.Calendar current = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        current.setTimeInMillis(currentTime);
        int currentHour = current.get(java.util.Calendar.HOUR_OF_DAY);
        int currentMinute = current.get(java.util.Calendar.MINUTE);
        int currentTimeMinutes = currentHour * 60 + currentMinute;
        
        java.util.Calendar connect = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        connect.setTimeInMillis(connectTimeUTC);
        int connectHour = connect.get(java.util.Calendar.HOUR_OF_DAY);
        int connectMinute = connect.get(java.util.Calendar.MINUTE);
        int startTimeMinutes = connectHour * 60 + connectMinute;
        
        java.util.Calendar disconnect = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        disconnect.setTimeInMillis(disconnectTimeUTC);
        int disconnectHour = disconnect.get(java.util.Calendar.HOUR_OF_DAY);
        int disconnectMinute = disconnect.get(java.util.Calendar.MINUTE);
        int endTimeMinutes = disconnectHour * 60 + disconnectMinute;
        
        // Handle overnight schedules like iOS
        if (startTimeMinutes < endTimeMinutes) {
            // Same day schedule: [start, end]
            return currentTimeMinutes >= startTimeMinutes && currentTimeMinutes <= endTimeMinutes;
        } else {
            // Overnight schedule: [start, 1440) ∪ [0, end]
            return currentTimeMinutes >= startTimeMinutes || currentTimeMinutes <= endTimeMinutes;
        }
    }
    
    /**
     * Check if it's start time like iOS
     * @return true if current time matches the start time
     */
    public boolean isStartTime() {
        long currentTime = System.currentTimeMillis();
        
        // Convert to minutes for easier comparison (like iOS)
        java.util.Calendar current = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        current.setTimeInMillis(currentTime);
        int currentHour = current.get(java.util.Calendar.HOUR_OF_DAY);
        int currentMinute = current.get(java.util.Calendar.MINUTE);
        
        java.util.Calendar connect = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        connect.setTimeInMillis(connectTimeUTC);
        int connectHour = connect.get(java.util.Calendar.HOUR_OF_DAY);
        int connectMinute = connect.get(java.util.Calendar.MINUTE);
        
        return currentHour == connectHour && currentMinute == connectMinute;
    }
    
    /**
     * Check if it's end time like iOS
     * @return true if current time matches the end time
     */
    public boolean isEndTime() {
        long currentTime = System.currentTimeMillis();
        
        // Convert to minutes for easier comparison (like iOS)
        java.util.Calendar current = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        current.setTimeInMillis(currentTime);
        int currentHour = current.get(java.util.Calendar.HOUR_OF_DAY);
        int currentMinute = current.get(java.util.Calendar.MINUTE);
        int currentTimeMinutes = currentHour * 60 + currentMinute;
        
        java.util.Calendar connect = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        connect.setTimeInMillis(connectTimeUTC);
        int connectHour = connect.get(java.util.Calendar.HOUR_OF_DAY);
        int connectMinute = connect.get(java.util.Calendar.MINUTE);
        int startTimeMinutes = connectHour * 60 + connectMinute;
        
        java.util.Calendar disconnect = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        disconnect.setTimeInMillis(disconnectTimeUTC);
        int disconnectHour = disconnect.get(java.util.Calendar.HOUR_OF_DAY);
        int disconnectMinute = disconnect.get(java.util.Calendar.MINUTE);
        int endTimeMinutes = disconnectHour * 60 + disconnectMinute;
        
        // Handle overnight schedules like iOS
        if (startTimeMinutes < endTimeMinutes) {
            // Same day schedule: end time is exclusive
            return currentTimeMinutes >= endTimeMinutes;
        } else {
            // Overnight schedule: end time is the first minute outside the window
            return currentTimeMinutes >= endTimeMinutes && currentTimeMinutes < startTimeMinutes;
        }
    }
    
    /**
     * Validate schedule parameters for debugging and testing
     * @return Validation result with details
     */
    public String validateSchedule() {
        long currentTime = System.currentTimeMillis();
        StringBuilder result = new StringBuilder();
        
        result.append("Schedule Validation:\n");
        result.append("- Connect Time: ").append(new java.util.Date(connectTimeUTC)).append("\n");
        result.append("- Disconnect Time: ").append(new java.util.Date(disconnectTimeUTC)).append("\n");
        result.append("- Current Time: ").append(new java.util.Date(currentTime)).append("\n");
        result.append("- Is Immediate: ").append(isImmediateConnection()).append("\n");
        result.append("- Is Previous Day Start: ").append(isPreviousDayStart()).append("\n");
        result.append("- Is Overnight: ").append(disconnectTimeUTC < connectTimeUTC).append("\n");
        result.append("- Should Trigger Now: ").append(shouldTriggerAt(currentTime)).append("\n");
        result.append("- Should Disconnect Now: ").append(shouldDisconnectAt(currentTime)).append("\n");
        
        return result.toString();
    }
    
}
