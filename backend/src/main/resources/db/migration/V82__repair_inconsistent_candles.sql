-- V82__repair_inconsistent_candles.sql — a daily candle's high/low must contain its open and close. Three
-- stored candles (ASTS 2026-10-06/07, HUBB 2021-05-05) were off by a feed rounding hair; the indicator
-- library rejects such a bar, which made every chart-dependent agent fail for ASTS for days (fundamentals
-- refresh, Agent 5's review). PriceCandle now normalizes new rows; this repairs the existing ones.

update price_candles
set high = greatest(open, high, low, close),
    low  = least(open, high, low, close)
where low > least(open, close) or high < greatest(open, close) or low > high;
