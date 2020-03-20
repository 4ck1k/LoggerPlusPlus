package com.nccgroup.loggerplusplus.logentry;

import burp.*;
import com.nccgroup.loggerplusplus.LoggerPlusPlus;
import com.nccgroup.loggerplusplus.filter.colorfilter.ColorFilter;
import com.nccgroup.loggerplusplus.logview.LogViewPanel;
import com.nccgroup.loggerplusplus.util.MoreHelp;
import com.nccgroup.loggerplusplus.util.NamedThreadFactory;
import com.nccgroup.loggerplusplus.util.PausableThreadPoolExecutor;

import javax.swing.*;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nccgroup.loggerplusplus.util.Globals.*;

/**
 * Created by corey on 07/09/17.
 */
public class LogManager implements IHttpListener, IProxyListener {
    public static final SimpleDateFormat LOGGER_DATE_FORMAT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    public static final SimpleDateFormat SERVER_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
    private final String instanceIdentifier = String.format("%02d", (int)Math.floor((Math.random()*100)));

    private final List<LogEntry> logEntries;
    private AtomicInteger pendingImport;

    private final ConcurrentHashMap<Integer, UUID> proxyIdToUUIDMap;
    private final ConcurrentHashMap<UUID, LogEntry> pendingRequests;
    private final ConcurrentHashMap<UUID, EntryPendingResponse> requestsAwaitingResponse;
    private final List<LogEntryListener> logEntryListeners;
    private final PausableThreadPoolExecutor entryProcessExecutor;
    private final PausableThreadPoolExecutor entryImportExecutor;
    private final ScheduledExecutorService cleanupExecutor;
    //Stats
    private AtomicInteger totalRequests;
    private AtomicInteger lateResponses;

    private Future importFuture;

    /**
     * Capture incoming requests and responses.
     * Logic to allow requests independently and match them to responses once received.
     * TODO SQLite integration
     * TODO Capture requests modified after logging using request obtained from response objects.
     */
    public LogManager(){
        totalRequests = new AtomicInteger(0);
        pendingImport = new AtomicInteger(0);
        lateResponses = new AtomicInteger(0);

        logEntries = Collections.synchronizedList(new ArrayList<>());

        logEntryListeners = new ArrayList<>();
        proxyIdToUUIDMap = new ConcurrentHashMap<>();
        pendingRequests = new ConcurrentHashMap<>();
        requestsAwaitingResponse = new ConcurrentHashMap<>();
        entryProcessExecutor = new PausableThreadPoolExecutor(10, 10,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new NamedThreadFactory("LPP-LogManager"));
        entryImportExecutor = new PausableThreadPoolExecutor(10, 10, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new NamedThreadFactory("LPP-Import"));

        //Create incomplete request cleanup thread so map doesn't get too big.
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("LPP-LogManager-Cleanup"));
        cleanupExecutor.scheduleAtFixedRate(new AbandonedRequestCleanupRunnable(),30000L, 30000L, TimeUnit.MILLISECONDS);
    }

    /**
     * Process messages from all tools but proxy.
     * Adds to queue for later processing.
     * @param toolFlag Tool used to make request
     * @param isRequestOnly If the message is request only or complete with response
     * @param httpMessage The request and potentially response received.
     */
    @Override
    public void processHttpMessage(final int toolFlag, final boolean isRequestOnly, final IHttpRequestResponse httpMessage) {
        if(toolFlag == IBurpExtenderCallbacks.TOOL_PROXY) return; //Proxy messages handled by proxy method
        if(httpMessage == null || !(Boolean) LoggerPlusPlus.preferences.getSetting(PREF_ENABLED) || !isValidTool(toolFlag)) return;
        Date arrivalTime = new Date();

        if(!(Boolean) LoggerPlusPlus.preferences.getSetting(PREF_LOG_OTHER_LIVE)){
            //Submit normally, we're not tracking requests and responses separately.
            if(!isRequestOnly) { //But only add entries complete with a response.
                final LogEntry logEntry = new LogEntry(toolFlag, arrivalTime, httpMessage);
                entryProcessExecutor.submit(createNewEntryRunnable(logEntry));
            }
            return;
        }

        if(isRequestOnly){
            final LogEntry logEntry = new LogEntry(toolFlag, arrivalTime, httpMessage);
            //Tag the request with the UUID in the comment field, as this persists for when we get the response back!
            LogManagerHelper.tagRequestResponseWithUUID(instanceIdentifier, logEntry.getIdentifier(), httpMessage);
            //Store the identifier so we know its pending its response
            pendingRequests.put(logEntry.getIdentifier(), logEntry);
            //Create a task to be executed at some point in the future, and queue it.
            entryProcessExecutor.submit(createNewEntryRunnable(logEntry));
            return;
        }else{
            UUID uuid = LogManagerHelper.extractAndRemoveUUIDFromRequestResponseComment(instanceIdentifier, httpMessage);
            if(uuid != null) {
                updateRequestWithResponse(uuid, arrivalTime, httpMessage);
            }
        }
    }

    /**
     * Process messages received from the proxy tool.
     * For requests, a new processing job is added to the executor.
     * @param isRequestOnly
     * @param proxyMessage
     */
    @Override
    public void processProxyMessage(final boolean isRequestOnly, final IInterceptedProxyMessage proxyMessage) {
        final int toolFlag = IBurpExtenderCallbacks.TOOL_PROXY;
        if(proxyMessage == null || !(Boolean) LoggerPlusPlus.preferences.getSetting(PREF_ENABLED) || !isValidTool(toolFlag)) return;
        Date arrivalTime = new Date();

        if(isRequestOnly){
            //The request is not yet sent, process the request object
            final LogEntry logEntry = new LogEntry(toolFlag, arrivalTime, proxyMessage.getMessageInfo());
            //Store our proxy specific info now.
            logEntry.clientIP = String.valueOf(proxyMessage.getClientIpAddress());
            logEntry.listenerInterface = proxyMessage.getListenerInterface();

            //Make a note of the entry UUID corresponding to the message identifier.
            proxyIdToUUIDMap.put(proxyMessage.getMessageReference(), logEntry.getIdentifier());
            //Store the identifier so we know its pending its response
            pendingRequests.put(logEntry.getIdentifier(), logEntry);
            //Create a task to be executed at some point in the future, and queue it.
            entryProcessExecutor.submit(createNewEntryRunnable(logEntry));
        }else{
            //We're handling a response.
            UUID uuid = proxyIdToUUIDMap.remove(proxyMessage.getMessageReference());
            if(uuid != null){
                updateRequestWithResponse(uuid, arrivalTime, proxyMessage.getMessageInfo());
            }
        }
    }

    /**
     * When a response comes in, determine if the request has already been processed or not.
     * If it has not yet been processed, add the response information to the entry and let the original job handle it.
     * Otherwise, create a new job to process the response.
     * Unknown UUID's signify the response arrived after the pending request was cleaned up.
     * @param entryIdentifier The unique UUID for the log entry.
     * @param arrivalTime The arrival time of the response.
     * @param requestResponse The HTTP request response object.
     */
    private void updateRequestWithResponse(UUID entryIdentifier, Date arrivalTime, IHttpRequestResponse requestResponse){
        if(pendingRequests.containsKey(entryIdentifier)){
            //The entry has not been processed yet! We can add its response data before its processed.

            //We must remove it from the pendingRequests list to prevent memory leaks.
            final LogEntry logEntry = pendingRequests.remove(entryIdentifier);

            synchronized (logEntry) {
                //Update the requestResponse with the new one, and tell it when it arrived.
                logEntry.addResponse(arrivalTime, requestResponse);
            }

            //Do nothing now, there's already a runnable submitted to process it somewhere in the queue.
            return;

        }else if(requestsAwaitingResponse.containsKey(entryIdentifier)){

            //The entry has already been processed, we must update it with the response.
            EntryPendingResponse entryPendingResponse = requestsAwaitingResponse.remove(entryIdentifier);

            //Create and submit a job for the processing of its response.
            Runnable process = createEntryUpdateRunnable(arrivalTime, entryPendingResponse, requestResponse);
            entryProcessExecutor.submit(process);
        }else{
            //Unknown UUID. Potentially for a request which was cleaned up already.
            handleExpiredResponse(requestResponse);
        }
    }

    /**
     * Create a runnable to be used in an executor which will process a
     * HTTP object and store the results in the provided LogEntry object.
     * If the response is not present, store the log entry in the pending response map
     * so we can process the response separately once it is received.
     * @param logEntry The LogEntry object which will store the processed results
     * @return Runnable for use in executor to process the entry.
     */
    private Runnable createNewEntryRunnable(final LogEntry logEntry){
        return () -> {
            try {
                IRequestInfo analyzedReq = LoggerPlusPlus.callbacks.getHelpers().analyzeRequest(logEntry.requestResponse);
                URL uUrl = analyzedReq.getUrl();
                if (!shouldLog(uUrl)) return; //Do not log out of scope items.

                //We will always have a request to work with at least.
                logEntry.processRequest(analyzedReq);

                if (logEntry.requestResponse.getResponse() == null) {
                    //We're waiting on the response, move the entry from pending to the waitingForResponse list
                    EntryPendingResponse entryPendingResponse = moveEntryToPendingResponse(logEntry);
                    int modelIndex = addNewRequest(logEntry, false);
                    entryPendingResponse.setModelIndex(modelIndex);
                } else {
                    //Either we got the initial request with the response
                    //Or the response was received before we started processing.
                    logEntry.processResponse();

                    //We have our request, and our response!
                    addNewRequest(logEntry, true);

                    //We're done with processing the entry. We can finalize it.
                    finalizeEntry(logEntry);
                }
            }catch (Exception e){
                e.printStackTrace();
            } finally {
                if (logEntry.isImported()) pendingImport.getAndDecrement();
            }
        };
    }

    /**
     * Used to update entries which have already been added to the table, but were waiting on a response.
     * @param arrivalTime
     * @param entryAwaitingResponse
     * @param requestResponse
     * @return
     */
    private Runnable createEntryUpdateRunnable(final Date arrivalTime, final EntryPendingResponse entryAwaitingResponse,
                                               final IHttpRequestResponse requestResponse){
        return () -> {
            final LogEntry logEntry = entryAwaitingResponse.getLogEntry();
            synchronized (logEntry) {
                logEntry.addResponse(arrivalTime, requestResponse);
                logEntry.processResponse();

                HashMap<UUID, ColorFilter> colorFilters = LoggerPlusPlus.preferences.getSetting(PREF_COLOR_FILTERS);
                for (ColorFilter colorFilter : colorFilters.values()) {
                    logEntry.testColorFilter(colorFilter, true);
                }

                for (LogEntryListener logEntryListener : logEntryListeners) {
                    logEntryListener.onResponseUpdated(entryAwaitingResponse.getModelIndex(), logEntry);
                }

                //We're done processing the entry now. We can finalize it.
                finalizeEntry(logEntry);
            }
        };
    }

    /**
     * Adds the new request
     * @param logEntry
     * @param hasResponse
     */
    private int addNewRequest(LogEntry logEntry, boolean hasResponse){
        //After handling request / response logEntries generation.

        int modelIndex;
        int removedEntries = 0;
        while (logEntries.size() >= getMaximumEntries()) {
            final LogEntry removedLogEntry = logEntries.get(0);
            removeLogEntry(removedLogEntry);
            removedEntries++;
        }

        if(removedEntries > 0) {
            //Update model indices of entries pending their responses
            for (EntryPendingResponse entry : this.requestsAwaitingResponse.values()) {
                entry.setModelIndex(entry.getModelIndex() - removedEntries);
            }
        }

        //Add to grepTable / modify existing entry.
        HashMap<UUID,ColorFilter> colorFilters = LoggerPlusPlus.preferences.getSetting(PREF_COLOR_FILTERS);
        for (ColorFilter colorFilter : colorFilters.values()) {
            logEntry.testColorFilter(colorFilter, false);
        }

        logEntries.add(logEntry);
        modelIndex = logEntries.size()-1;

        for (LogEntryListener listener : logEntryListeners) {
            listener.onRequestAdded(modelIndex, logEntry, hasResponse);
        }

        totalRequests.getAndIncrement();

        //Finally, return the model index for the entry so we can
        //use it later if we're dealing with an incomplete request.
        return modelIndex;
    }

    private void finalizeEntry(LogEntry logEntry){
        //Clean the entry up and remove any leftover processing artifacts.

    }

    private synchronized void handleExpiredResponse(IHttpRequestResponse requestResponse){
        lateResponses.incrementAndGet();
        int totalReqs = totalRequests.get();
        if(totalReqs > 100 && ((float)lateResponses.get())/totalReqs > 0.17){
            MoreHelp.showWarningMessage(lateResponses + " responses have been delivered after the Logger++ timeout. Consider increasing this value.");
            //Reset late responses to prevent message being displayed again so soon.
            lateResponses.set(0);
        }
    }

    public void removeLogEntry(LogEntry logEntry){
        int index = logEntries.indexOf(logEntry);
        for (LogEntryListener listener : logEntryListeners) {
            listener.onRequestRemoved(index, logEntry);
        }
    }

    private EntryPendingResponse moveEntryToPendingResponse(LogEntry logEntry){
        EntryPendingResponse entryAwaitingResponse = new EntryPendingResponse(logEntry);
        pendingRequests.remove(logEntry.getIdentifier());
        requestsAwaitingResponse.put(logEntry.getIdentifier(), entryAwaitingResponse);
        return entryAwaitingResponse;
    }

    public void importExisting(IHttpRequestResponse requestResponse) {
        final LogEntry logEntry = new LogEntry(IBurpExtenderCallbacks.TOOL_PROXY, requestResponse);
        logEntry.setImported(true);
        pendingImport.getAndIncrement();
        entryImportExecutor.submit(createNewEntryRunnable(logEntry));
    }

    public void importProxyHistory(boolean askConfirmation){
        //TODO Fix time bug for imported results. Multithreading means results will likely end up mixed.

        int result = JOptionPane.OK_OPTION;
        int historySize = LoggerPlusPlus.callbacks.getProxyHistory().length;
        int maxEntries = LoggerPlusPlus.preferences.getSetting(PREF_MAXIMUM_ENTRIES);
        if(askConfirmation) {
            String message = "Import " + historySize + " items from burp suite proxy history? This will clear the current entries." +
                    "\nLarge imports may take a few minutes to process.";
            if(historySize > maxEntries) {
                message += "\nNote: History will be truncated to " + maxEntries + " entries.";
            }

            result = MoreHelp.askConfirmMessage("Burp Proxy Import",
                    message, new String[]{"Import", "Cancel"});
        }
        if(result == JOptionPane.OK_OPTION) {
            importFuture = entryProcessExecutor.submit(() -> {
                this.reset(); //Clear existing entries
                entryProcessExecutor.pause(); //Do not process new entries yet.

                LogViewPanel logViewPanel = LoggerPlusPlus.instance.getLogViewPanel();
                IHttpRequestResponse[] history = LoggerPlusPlus.callbacks.getProxyHistory();

                int startIndex = Math.max(0, history.length-maxEntries);
                int importCount = historySize - startIndex;
                logViewPanel.showImportProgress(importCount); //TODO Use listeners instead

                for (int index = startIndex; index < history.length; index++) {
                    importExisting(history[index]);
                }
                int pending;
                while((pending = pendingImport.get()) != 0 && !Thread.currentThread().isInterrupted()){
                    try {
                        Thread.sleep(500);
//                        LoggerPlusPlus.instance.logOutput("Importing logs, " + pending + " entries remaining.");
                        logViewPanel.setProgressValue(importCount - pending);
                    } catch (InterruptedException e) {}
                }

                entryProcessExecutor.resume();

                //All imported
                LoggerPlusPlus.instance.getLogViewPanel().showLogTable();
            });
        }
    }

    public List<LogEntry> getLogEntries() {
        return logEntries;
    }

    private boolean isValidTool(int toolFlag){
        return ((Boolean) LoggerPlusPlus.preferences.getSetting(PREF_LOG_GLOBAL) ||
                ((Boolean) LoggerPlusPlus.preferences.getSetting(PREF_LOG_PROXY) && toolFlag== IBurpExtenderCallbacks.TOOL_PROXY) ||
                ((Boolean) LoggerPlusPlus.preferences.getSetting(PREF_LOG_INTRUDER) && toolFlag== IBurpExtenderCallbacks.TOOL_INTRUDER) ||
                ((Boolean) LoggerPlusPlus.preferences.getSetting(PREF_LOG_REPEATER) && toolFlag== IBurpExtenderCallbacks.TOOL_REPEATER) ||
                ((Boolean) LoggerPlusPlus.preferences.getSetting(PREF_LOG_SCANNER) && toolFlag== IBurpExtenderCallbacks.TOOL_SCANNER) ||
                ((Boolean) LoggerPlusPlus.preferences.getSetting(PREF_LOG_SEQUENCER) && toolFlag== IBurpExtenderCallbacks.TOOL_SEQUENCER) ||
                ((Boolean) LoggerPlusPlus.preferences.getSetting(PREF_LOG_SPIDER) && toolFlag== IBurpExtenderCallbacks.TOOL_SPIDER) ||
                ((Boolean) LoggerPlusPlus.preferences.getSetting(PREF_LOG_EXTENDER) && toolFlag== IBurpExtenderCallbacks.TOOL_EXTENDER) ||
                ((Boolean) LoggerPlusPlus.preferences.getSetting(PREF_LOG_TARGET_TAB) && toolFlag== IBurpExtenderCallbacks.TOOL_TARGET));
    }

    private boolean shouldLog(URL url){
        return (!(Boolean) LoggerPlusPlus.preferences.getSetting(PREF_RESTRICT_TO_SCOPE)
                || LoggerPlusPlus.callbacks.isInScope(url));
    }

    public void addLogListener(LogEntryListener listener) {
        logEntryListeners.add(listener);
    }
    public void removeLogListener(LogEntryListener listener) {
        logEntryListeners.remove(listener);
    }
    public List<LogEntryListener> getLogEntryListeners() {
        return logEntryListeners;
    }

    public int getMaximumEntries() {
        return (int) LoggerPlusPlus.preferences.getSetting(PREF_MAXIMUM_ENTRIES);
    }

    public void reset() {
        this.logEntries.clear();
        this.proxyIdToUUIDMap.clear();
        this.lateResponses.set(0);
        this.totalRequests.set(0);

        for (LogEntryListener logEntryListener : logEntryListeners) {
            logEntryListener.onLogsCleared();
        }
    }

    public void shutdown(){
        this.cleanupExecutor.shutdownNow();
        this.entryProcessExecutor.shutdownNow();
        this.logEntryListeners.clear();
        if(!importFuture.isDone()){
            importFuture.cancel(true);
        }
    }

    private class AbandonedRequestCleanupRunnable implements Runnable {

        @Override
        public void run() {
            long timeNow = new Date().getTime();
            synchronized (requestsAwaitingResponse){
                try {
                    HashSet<UUID> removedUUIDs = new HashSet<>();
                    Iterator<Map.Entry<UUID, EntryPendingResponse>> iter
                            = requestsAwaitingResponse.entrySet().iterator();

                    while (iter.hasNext()) {
                        Map.Entry<UUID, EntryPendingResponse> abandonedEntry = iter.next();
                        LogEntry logEntry = abandonedEntry.getValue().getLogEntry();
                        if(logEntry.requestDateTime == null){
                            //Should never be the case.
                            //Entries should always have request times unless they are imported,
                            //In which case they will never be awaiting a response so never in this list.
                            return;
                        }
                        long entryTime = logEntry.requestDateTime.getTime();
                        long responseTimeout = 1000 * ((Integer) LoggerPlusPlus.preferences.getSetting(PREF_RESPONSE_TIMEOUT)).longValue();
                        if (timeNow - entryTime > responseTimeout) {
                            iter.remove();
                            LogManagerHelper.extractAndRemoveUUIDFromRequestResponseComment(instanceIdentifier, logEntry.requestResponse);
                            logEntry.requestResponse.setComment("Timed Out " + logEntry.requestResponse.getComment());
                            removedUUIDs.add(abandonedEntry.getKey());
                        }
                    }

                    Iterator<Map.Entry<Integer, UUID>> proxyMapIter = proxyIdToUUIDMap.entrySet().iterator();
                    while (proxyMapIter.hasNext()) {
                        Map.Entry<Integer, UUID> entry = proxyMapIter.next();
                        if (removedUUIDs.contains(entry.getValue())) {
                            iter.remove();
                        }
                    }

                    if (removedUUIDs.size() > 0) {
                        LoggerPlusPlus.instance.logOutput("Cleaned Up " + removedUUIDs.size()
                                + " proxy requests without a response after the specified timeout.");
                    }
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
    }
}
