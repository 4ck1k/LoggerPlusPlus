package com.nccgroup.loggerplusplus.filterlibrary;

import com.coreyd97.BurpExtenderUtilities.Preferences;
import com.nccgroup.loggerplusplus.filter.colorfilter.ColorFilter;
import com.nccgroup.loggerplusplus.filter.colorfilter.ColorFilterListener;
import com.nccgroup.loggerplusplus.filter.logfilter.LogFilter;
import com.nccgroup.loggerplusplus.util.Globals;
import com.nccgroup.loggerplusplus.filter.savedfilter.SavedFilter;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class FilterLibraryController {

    private final Preferences preferences;
    private final FilterLibraryPanel panel;
    private final ArrayList<SavedFilter> savedFilters;
    private final ArrayList<FilterLibraryListener> listeners;
    private final HashMap<UUID, ColorFilter> colorFilters;
    private final ArrayList<ColorFilterListener> colorFilterListeners;

    public FilterLibraryController(Preferences preferences){
        this.preferences = preferences;
        this.listeners = new ArrayList<>();
        this.colorFilterListeners = new ArrayList<>();
        this.savedFilters = preferences.getSetting(Globals.PREF_SAVED_FILTERS);
        this.colorFilters = preferences.getSetting(Globals.PREF_COLOR_FILTERS);
        this.panel = new FilterLibraryPanel(this);
    }

    public FilterLibraryPanel getUIComponent() {
        return panel;
    }

    public ArrayList<SavedFilter> getSavedFilters(){
        return this.savedFilters;
    }

    public void addFilter(SavedFilter savedFilter){
        synchronized (this.savedFilters) {
            this.savedFilters.add(savedFilter);
        }
        for (FilterLibraryListener listener : this.listeners) {
            try{
                listener.onFilterAdded(savedFilter);
            }catch (Exception e){
                e.printStackTrace();
            }
        }
        saveFilters();
    }

    public void removeFilter(SavedFilter filter){
        synchronized (this.savedFilters){
            this.savedFilters.remove(filter);
        }
        for (FilterLibraryListener listener : this.listeners) {
            try{
                listener.onFilterRemoved(filter);
            }catch (Exception e){
                e.printStackTrace();
            }
        }
        saveFilters();
    }

    public void saveFilters(){
        this.preferences.setSetting(Globals.PREF_SAVED_FILTERS, savedFilters);
    }

    public void addFilterListener(FilterLibraryListener listener){
        this.listeners.add(listener);
    }

    public void removeFilterListener(FilterLibraryListener listener){
        this.listeners.remove(listener);
    }

    public HashMap<UUID, ColorFilter> getColorFilters() {
        return colorFilters;
    }

    public void addColorFilter(String title, LogFilter filter){
        this.addColorFilter(title, filter, Color.BLACK, Color.WHITE);
    }

    public void addColorFilter(String title, LogFilter filter, Color foreground, Color background){
        this.addColorFilter(new ColorFilter(title, filter, foreground, background));
    }

    public void addColorFilter(ColorFilter colorFilter){
        this.colorFilters.put(colorFilter.getUUID(), colorFilter);

        for (ColorFilterListener colorFilterListener : this.colorFilterListeners) {
            try {
                colorFilterListener.onFilterAdd(colorFilter);
            }catch (Exception ignored){}
        }
        saveColorFilters();
    }

    public void removeColorFilter(ColorFilter colorFilter){
        synchronized (this.colorFilters){
            this.colorFilters.remove(colorFilter);
        }
        for (ColorFilterListener listener : this.colorFilterListeners) {
            try{
                listener.onFilterRemove(colorFilter);
            }catch (Exception e){
                e.printStackTrace();
            }
        }
        saveColorFilters();
    }

    //Called when a filter is modified.
    public void updateColorFilter(ColorFilter colorFilter){
        for (ColorFilterListener listener : this.colorFilterListeners) {
            try{
                listener.onFilterChange(colorFilter);
            }catch (Exception e){
                e.printStackTrace();
            }
        }
        saveColorFilters();
    }

    public void saveColorFilters(){
        this.preferences.setSetting(Globals.PREF_COLOR_FILTERS, colorFilters);
    }

    public void addColorFilterListener(ColorFilterListener listener){
        this.colorFilterListeners.add(listener);
    }

    public void removeColorFilterListener(ColorFilterListener listener){
        this.colorFilterListeners.remove(listener);
    }


}
