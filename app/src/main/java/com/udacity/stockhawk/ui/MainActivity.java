package com.udacity.stockhawk.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.udacity.stockhawk.R;
import com.udacity.stockhawk.data.Contract;
import com.udacity.stockhawk.data.PrefUtils;
import com.udacity.stockhawk.sync.QuoteSyncJob;

import java.util.Calendar;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import timber.log.Timber;

import static com.udacity.stockhawk.sync.QuoteSyncJob.ACTION_DATA_UPDATED;

public class MainActivity extends AppCompatActivity implements LoaderManager.LoaderCallbacks<Cursor>,
        SwipeRefreshLayout.OnRefreshListener,
        StockAdapter.StockAdapterOnClickHandler {

    private static final int STOCK_LOADER = 0;
    @SuppressWarnings("WeakerAccess")
    @BindView(R.id.recycler_view)
    RecyclerView stockRecyclerView;
    @SuppressWarnings("WeakerAccess")
    @BindView(R.id.swipe_refresh)
    SwipeRefreshLayout swipeRefreshLayout;
    @SuppressWarnings("WeakerAccess")
    @BindView(R.id.error)
    TextView error;
    private StockAdapter adapter;
    boolean useDetailActivity;
    private Intent mIntent;
    private String clickedSymbol;
    private static final String KEY_SYMBOL = "symbolSavedInstance";

    private BroadcastReceiver mErrorAddingStocksReceiver;
    private IntentFilter mErrorAddingStocksIntentFilter;

    @Override
    public void onClick(String symbol) {
        Timber.d("Symbol clicked: %s", symbol);
        if (useDetailActivity) {
            Intent intent = DetailActivity.buildIntent(this, symbol);
            startActivity(intent);
        } else {
            adapter.toggleChartData(symbol);
            clickedSymbol = symbol;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mIntent = getIntent();
        clickedSymbol = mIntent.getStringExtra(DetailActivity.EXTRA_SYMBOL);
        if (null!=savedInstanceState){
            clickedSymbol = savedInstanceState.getString(KEY_SYMBOL);
        }

        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        useDetailActivity = getResources().getBoolean(R.bool.use_detail_activity);

        adapter = new StockAdapter(this, this);
        stockRecyclerView.setAdapter(adapter);
        stockRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        swipeRefreshLayout.setOnRefreshListener(this);
        swipeRefreshLayout.setRefreshing(true);
        onRefresh();

        QuoteSyncJob.initialize(this);
        getSupportLoaderManager().initLoader(STOCK_LOADER, null, this);

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                String symbol = adapter.getSymbolAtPosition(viewHolder.getAdapterPosition());
                PrefUtils.removeStock(MainActivity.this, symbol);
                getContentResolver().delete(Contract.Quote.makeUriForStock(symbol), null, null);
                QuoteSyncJob.setNetworkStatus(MainActivity.this, QuoteSyncJob.SERVER_INVALID);
                Intent dataUpdatedIntent = new Intent(ACTION_DATA_UPDATED);
                sendBroadcast(dataUpdatedIntent);
            }
        }).attachToRecyclerView(stockRecyclerView);

        mErrorAddingStocksReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(getString(R.string.broadcast_invalid_stock))){
                    String symbol = intent.getStringExtra(QuoteSyncJob.EXTRA_INVALID_STOCK_NAME);
                    Toast.makeText(MainActivity.this, getString(R.string.toast_invalid_stock_name_FORMAT, symbol), Toast.LENGTH_LONG)
                            .show();
                }
            }
        };
        mErrorAddingStocksIntentFilter = new IntentFilter();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(KEY_SYMBOL, clickedSymbol);
        super.onSaveInstanceState(outState);
    }

    private boolean networkUp() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = cm.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.isConnectedOrConnecting();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mErrorAddingStocksIntentFilter.addAction(getString(R.string.broadcast_invalid_stock));
        registerReceiver(mErrorAddingStocksReceiver, mErrorAddingStocksIntentFilter);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(mErrorAddingStocksReceiver);
        super.onStop();
    }

    @Override
    public void onRefresh() {

        QuoteSyncJob.syncImmediately(this);

        if (!networkUp() && adapter.getItemCount() == 0) {
            swipeRefreshLayout.setRefreshing(false);
            error.setText(getString(R.string.error_no_network));
            error.setVisibility(View.VISIBLE);
        } else if (!networkUp()) {
            swipeRefreshLayout.setRefreshing(false);
            Toast.makeText(this, R.string.toast_no_connectivity, Toast.LENGTH_LONG).show();
        } else if (adapter.getItemCount() == 0) {
            swipeRefreshLayout.setRefreshing(false);
            error.setText(getString(R.string.error_no_stocks));
            error.setVisibility(View.VISIBLE);
        } else {
            error.setVisibility(View.GONE);
        }
        adapter.clearExpandedIndices();
    }

    @OnClick(R.id.fab)
    public void button(@SuppressWarnings("UnusedParameters") View view) {
        new AddStockDialog().show(getFragmentManager(), "StockDialogFragment");
    }

    void addStock(String symbol) {
        if (symbol != null && !symbol.isEmpty()) {

            if (networkUp()) {
                swipeRefreshLayout.setRefreshing(true);
            } else {
                String message = getString(R.string.toast_stock_added_no_connectivity, symbol);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }

            PrefUtils.addStock(this, symbol);
            QuoteSyncJob.syncImmediately(this);
        }
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return new CursorLoader(this,
                Contract.Quote.URI,
                Contract.Quote.QUOTE_COLUMNS.toArray(new String[]{}),
                null, null, Contract.Quote.COLUMN_SYMBOL);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        swipeRefreshLayout.setRefreshing(false);

        if (data.getCount() != 0) {
            error.setVisibility(View.GONE);
        } else {
            String errorMessage;
            int networkStatus = PrefUtils.getNetworkStatus(this);
            switch (networkStatus){
                case QuoteSyncJob.NETWORK_DOWN:
                    errorMessage = getString(R.string.error_no_network);
                    break;
                case QuoteSyncJob.SERVER_DOWN:
                    errorMessage = getString(R.string.error_server_error);
                    break;
                case QuoteSyncJob.SERVER_INVALID:
                    errorMessage = getString(R.string.error_no_stocks);
                    break;
                default:
                    errorMessage = getString(R.string.error_unknown);
                    break;
            }
            error.setText(errorMessage);
            error.setVisibility(View.VISIBLE);
        }
        adapter.setCursor(data);
        long currentTime = Calendar.getInstance().getTimeInMillis();
        if (currentTime - adapter.mLowestDate > 6.048e+8){
            Timber.d("current: " + Long.toString(currentTime) + ", adapter lowest: " + Long.toString(adapter.mLowestDate));
            Snackbar.make(findViewById(R.id.swipe_refresh), R.string.stocks_out_of_date, Snackbar.LENGTH_LONG)
                    .setAction(R.string.refresh, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            onRefresh();
                        }
                    }).setActionTextColor(getResources().getColor(R.color.colorAccent))
                    .show();
        }
        if (null!=clickedSymbol){
            onClick(clickedSymbol);
        }
    }


    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        swipeRefreshLayout.setRefreshing(false);
        adapter.setCursor(null);
    }


    private void setDisplayModeMenuItemIcon(MenuItem item) {
        String displayMode = PrefUtils.getDisplayMode(this);
        if (displayMode
                .equals(getString(R.string.pref_display_mode_absolute_key))) {
            item.setIcon(R.drawable.ic_percentage);
        } else {
            item.setIcon(R.drawable.ic_dollar);
        }
        item.setTitle(getString(R.string.CD_display_mode, displayMode));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (useDetailActivity) {
            getMenuInflater().inflate(R.menu.main_activity_settings, menu);
            MenuItem item = menu.findItem(R.id.action_change_units);
            setDisplayModeMenuItemIcon(item);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_change_units) {
            PrefUtils.toggleDisplayMode(this);
            setDisplayModeMenuItemIcon(item);
            adapter.notifyDataSetChanged();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
