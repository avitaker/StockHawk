package com.udacity.stockhawk.ui;


import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.preference.PreferenceManager;
import android.support.annotation.IntDef;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.github.mikephil.charting.charts.Chart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.udacity.stockhawk.R;
import com.udacity.stockhawk.data.Contract;
import com.udacity.stockhawk.data.HistoryData;
import com.udacity.stockhawk.data.PrefUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import butterknife.BindView;
import butterknife.ButterKnife;
import timber.log.Timber;

import static com.udacity.stockhawk.R.id.chart;
import static com.udacity.stockhawk.data.HistoryData.separateHistory;
import static com.udacity.stockhawk.ui.DetailActivity.INDEX_HISTORY;
import static yahoofinance.quotes.QuotesProperty.Symbol;

class StockAdapter extends RecyclerView.Adapter<StockAdapter.StockViewHolder> {

    private final Context context;
    private final DecimalFormat dollarFormatWithPlus;
    private final DecimalFormat dollarFormat;
    private final DecimalFormat percentageFormat;
    private Cursor cursor;
    private final StockAdapterOnClickHandler clickHandler;
    private int VIEW_TYPE_NORMAL = 50;
    private int VIEW_TYPE_EXPANDED = 51;
    public ArrayList<Integer> mExpandedIndices;
    public long mLowestDate;


    StockAdapter(Context context, StockAdapterOnClickHandler clickHandler) {
        this.context = context;
        this.clickHandler = clickHandler;

        mLowestDate = Long.MAX_VALUE;

        dollarFormat = (DecimalFormat) NumberFormat.getCurrencyInstance(Locale.US);
        dollarFormatWithPlus = (DecimalFormat) NumberFormat.getCurrencyInstance(Locale.US);
        dollarFormatWithPlus.setPositivePrefix("+$");
        percentageFormat = (DecimalFormat) NumberFormat.getPercentInstance(Locale.getDefault());
        percentageFormat.setMaximumFractionDigits(2);
        percentageFormat.setMinimumFractionDigits(2);
        percentageFormat.setPositivePrefix("+");

        mExpandedIndices = new ArrayList<>(0);
    }

    void setCursor(Cursor cursor) {
        this.cursor = cursor;
        notifyDataSetChanged();
    }

    String getSymbolAtPosition(int position) {

        cursor.moveToPosition(position);
        return cursor.getString(Contract.Quote.POSITION_SYMBOL);
    }

    @Override
    public StockViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View item;
        if (viewType==VIEW_TYPE_NORMAL) {
            item = LayoutInflater.from(context).inflate(R.layout.list_item_quote, parent, false);
        } else {
            item = LayoutInflater.from(context).inflate(R.layout.list_item_quote_expanded, parent, false);
        }

        return new StockViewHolder(item);
    }

    @Override
    public void onBindViewHolder(StockViewHolder holder, int position) {

        cursor.moveToPosition(position);

        if (cursor.getString(Contract.Quote.POSITION_HISTORY).equals(Contract.Quote.INVALID_STOCK_HISTORY_MARKER)){
            holder.symbol.setText(context.getString(R.string.error_invalid_stock_name_FORMAT, cursor.getString(Contract.Quote.POSITION_SYMBOL)));
            holder.price.setVisibility(View.GONE);
            holder.change.setVisibility(View.GONE);
        } else {
            if (holder.price.getVisibility() == View.GONE){
                holder.price.setVisibility(View.VISIBLE);
                holder.change.setVisibility(View.VISIBLE);
            }
            String symbol = cursor.getString(Contract.Quote.POSITION_SYMBOL);
            holder.symbol.setText(symbol);
            holder.price.setText(dollarFormat.format(cursor.getFloat(Contract.Quote.POSITION_PRICE)));


            float rawAbsoluteChange = cursor.getFloat(Contract.Quote.POSITION_ABSOLUTE_CHANGE);
            float percentageChange = cursor.getFloat(Contract.Quote.POSITION_PERCENTAGE_CHANGE);

            if (rawAbsoluteChange > 0) {
                holder.change.setBackgroundResource(R.drawable.percent_change_pill_green);
            } else {
                holder.change.setBackgroundResource(R.drawable.percent_change_pill_red);
            }

            String change = dollarFormatWithPlus.format(rawAbsoluteChange);
            String percentage = percentageFormat.format(percentageChange / 100);

            if (context.getResources().getBoolean(R.bool.use_detail_activity)) {
                if (PrefUtils.getDisplayMode(context)
                        .equals(context.getString(R.string.pref_display_mode_absolute_key))) {
                    holder.change.setText(change);
                } else {
                    holder.change.setText(percentage);
                }
            } else {
                holder.change.setText(context.getString(R.string.format_change_detail, change, percentage));
            }

            String history = cursor.getString(cursor.getColumnIndex(Contract.Quote.COLUMN_HISTORY));
            Long lastDate = HistoryData.getLastHistoryDate(history);
            if (mLowestDate > lastDate){
                mLowestDate = lastDate;
            }
            if (mExpandedIndices.contains(position)){
                ArrayList<HistoryData> historyDatas = separateHistory(history);
                List<Entry> entries = new ArrayList<Entry>();
                for (int x = historyDatas.size()-1; x >=0; x--){
                    HistoryData data = historyDatas.get(x);
                    entries.add(new Entry(data.getDate(), data.getPrice()));
                }
                LineDataSet dataSet = new LineDataSet(entries, symbol);
                dataSet.setColor(context.getResources().getColor(R.color.colorPrimaryDark));
                dataSet.setValueTextSize(0);
                dataSet.setDrawCircleHole(false);
                dataSet.setDrawCircles(false);
                dataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
                LineData lineData = new LineData(dataSet);
                holder.chart.setData(lineData);
                holder.chart.setBackgroundColor(context.getResources().getColor(R.color.material_gray_600));
                holder.chart.setTouchEnabled(false);
                XAxis xAxis = holder.chart.getXAxis();
                xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
                xAxis.setValueFormatter(new DateAxisFormatter());
                holder.chart.invalidate();
                holder.chart.setVisibility(View.VISIBLE);
            }
        }

    }

    @Override
    public int getItemViewType(int position) {
        if (mExpandedIndices.contains(position))
            return VIEW_TYPE_EXPANDED;
        return VIEW_TYPE_NORMAL;
    }

    @Override
    public int getItemCount() {
        int count = 0;
        if (cursor != null) {
            count = cursor.getCount();
        }
        return count;
    }


    interface StockAdapterOnClickHandler {
        void onClick(String symbol);
    }

    class StockViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        @BindView(R.id.symbol)
        TextView symbol;

        @BindView(R.id.price)
        TextView price;

        @BindView(R.id.change)
        TextView change;

        @BindView(R.id.chart) @Nullable
        Chart chart;

        StockViewHolder(View itemView) {
            super(itemView);
            ButterKnife.bind(this, itemView);
            itemView.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            int adapterPosition = getAdapterPosition();
            cursor.moveToPosition(adapterPosition);
            int symbolColumn = cursor.getColumnIndex(Contract.Quote.COLUMN_SYMBOL);
            clickHandler.onClick(cursor.getString(symbolColumn));

        }


    }


    public void toggleChartData(String symbol) {

        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            Integer toToggle;
            do {
                if (cursor.getString(cursor.getColumnIndex(Contract.Quote.COLUMN_SYMBOL)).equals(symbol)) {
                    toToggle = cursor.getPosition();
                    if (mExpandedIndices.contains(toToggle)) {
                        mExpandedIndices.remove(toToggle);
                    } else {
                        mExpandedIndices.add(toToggle);
                    }
                    notifyDataSetChanged();
                }
            } while (cursor.moveToNext());
        } else {

        }
    }

    void clearExpandedIndices(){
        mExpandedIndices = new ArrayList<>(0);
    }

}
