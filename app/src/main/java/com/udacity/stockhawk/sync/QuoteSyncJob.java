package com.udacity.stockhawk.sync;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.preference.PreferenceManager;
import android.support.annotation.IntDef;

import com.udacity.stockhawk.R;
import com.udacity.stockhawk.data.Contract;
import com.udacity.stockhawk.data.PrefUtils;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import timber.log.Timber;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;
import yahoofinance.histquotes.HistoricalQuote;
import yahoofinance.histquotes.Interval;
import yahoofinance.quotes.stock.StockQuote;

import static com.udacity.stockhawk.R.id.change;
import static com.udacity.stockhawk.R.id.price;

public final class QuoteSyncJob {

    public static final String EXTRA_INVALID_STOCK_NAME = "extraInvalidStockName";
    private static final int ONE_OFF_ID = 2;
    public static final String ACTION_DATA_UPDATED = "com.udacity.stockhawk.ACTION_DATA_UPDATED";
    private static final int PERIOD = 300000;
    private static final int INITIAL_BACKOFF = 10000;
    private static final int PERIODIC_ID = 1;
    private static final int YEARS_OF_HISTORY = 2;

    private QuoteSyncJob() {
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({NETWORK_OK,NETWORK_DOWN,SERVER_DOWN, SERVER_INVALID})
    public @interface NetworkStatus{}

    public static final int NETWORK_OK = 0;
    public static final int NETWORK_DOWN = 1;
    public static final int SERVER_DOWN = 2;
    public static final int SERVER_INVALID = 3;

    static public void setNetworkStatus(Context c, @NetworkStatus int status){
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(c);
        sp.edit().putInt(c.getString(R.string.pref_network_status), status).commit();
    }

    static void getQuotes(Context context) {

        Timber.d("Running sync job");

        Calendar from = Calendar.getInstance();
        Calendar to = Calendar.getInstance();
        from.add(Calendar.YEAR, -YEARS_OF_HISTORY);

        try {

            Set<String> stockPref = PrefUtils.getStocks(context);
            Set<String> stockCopy = new HashSet<>();
            stockCopy.addAll(stockPref);
            String[] stockArray = stockPref.toArray(new String[stockPref.size()]);

            Timber.d("1" + stockCopy.toString() + stockArray.length);

            if (stockArray.length == 0) {
                setNetworkStatus(context, SERVER_INVALID);
            }
            else {
                Map<String, Stock> quotes = YahooFinance.get(stockArray);
                Iterator<String> iterator = stockCopy.iterator();

                Timber.d("2" + quotes.toString());

                ArrayList<ContentValues> quoteCVs = new ArrayList<>();

                while (iterator.hasNext()) {
                    String symbol = iterator.next();


                    Stock stock = quotes.get(symbol);
                    if (null==stock){
                        sendBroadcastInvalidStock(context, symbol);
                        PrefUtils.removeStock(context, symbol);
                        continue;
                    } else {
                        StockQuote quote = stock.getQuote();

                        if (null == quote.getPrice()) {
                            sendBroadcastInvalidStock(context, symbol);
                            PrefUtils.removeStock(context, symbol);
                            continue;
                        } else {

                            float price = quote.getPrice().floatValue();
                            float change = quote.getChange().floatValue();
                            float percentChange = quote.getChangeInPercent().floatValue();

                            // WARNING! Don't request historical data for a stock that doesn't exist!
                            // The request will hang forever X_x
                            List<HistoricalQuote> history = stock.getHistory(from, to, Interval.WEEKLY);

                            StringBuilder historyBuilder = new StringBuilder();

                            for (HistoricalQuote it : history) {
                                historyBuilder.append(it.getDate().getTimeInMillis());
                                historyBuilder.append(", ");
                                historyBuilder.append(it.getClose());
                                historyBuilder.append("\n");
                            }

                            ContentValues quoteCV = new ContentValues();
                            quoteCV.put(Contract.Quote.COLUMN_SYMBOL, symbol);
                            quoteCV.put(Contract.Quote.COLUMN_PRICE, price);
                            quoteCV.put(Contract.Quote.COLUMN_PERCENTAGE_CHANGE, percentChange);
                            quoteCV.put(Contract.Quote.COLUMN_ABSOLUTE_CHANGE, change);


                            quoteCV.put(Contract.Quote.COLUMN_HISTORY, historyBuilder.toString());

                            quoteCVs.add(quoteCV);
                        }
                    }

                }

                context.getContentResolver()
                        .bulkInsert(
                                Contract.Quote.URI,
                                quoteCVs.toArray(new ContentValues[quoteCVs.size()]));

                Intent dataUpdatedIntent = new Intent(ACTION_DATA_UPDATED);
                context.sendBroadcast(dataUpdatedIntent);
                setNetworkStatus(context, NETWORK_OK);
            }

        } catch (IOException exception) {
            Timber.e(exception, "Error fetching stock quotes");
            setNetworkStatus(context, SERVER_DOWN);
        }
    }

    private static void schedulePeriodic(Context context) {
        Timber.d("Scheduling a periodic task");


        JobInfo.Builder builder = new JobInfo.Builder(PERIODIC_ID, new ComponentName(context, QuoteJobService.class));


        builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD)
                .setBackoffCriteria(INITIAL_BACKOFF, JobInfo.BACKOFF_POLICY_EXPONENTIAL);


        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

        scheduler.schedule(builder.build());
    }


    public static synchronized void initialize(final Context context) {

        schedulePeriodic(context);
        syncImmediately(context);

    }

    public static synchronized void syncImmediately(Context context) {

        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = cm.getActiveNetworkInfo();
        if (networkInfo != null && networkInfo.isConnectedOrConnecting()) {
            Intent nowIntent = new Intent(context, QuoteIntentService.class);
            context.startService(nowIntent);
        } else {
            setNetworkStatus(context.getApplicationContext(), NETWORK_DOWN);
            JobInfo.Builder builder = new JobInfo.Builder(ONE_OFF_ID, new ComponentName(context, QuoteJobService.class));


            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(INITIAL_BACKOFF, JobInfo.BACKOFF_POLICY_EXPONENTIAL);


            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

            scheduler.schedule(builder.build());


        }
    }

    private static ContentValues makeInvalidContentValues(String symbol){
        ContentValues quoteCV = new ContentValues();
        quoteCV.put(Contract.Quote.COLUMN_SYMBOL, symbol);
        quoteCV.put(Contract.Quote.COLUMN_PRICE, 0f);
        quoteCV.put(Contract.Quote.COLUMN_PERCENTAGE_CHANGE, 0f);
        quoteCV.put(Contract.Quote.COLUMN_ABSOLUTE_CHANGE, 0f);


        quoteCV.put(Contract.Quote.COLUMN_HISTORY, Contract.Quote.INVALID_STOCK_HISTORY_MARKER);
        return quoteCV;
    }

    private static void sendBroadcastInvalidStock(Context context, String stockName){
        Intent intent = new Intent(context.getString(R.string.broadcast_invalid_stock));
        intent.putExtra(EXTRA_INVALID_STOCK_NAME, stockName);
        context.sendBroadcast(intent);
    }

}
