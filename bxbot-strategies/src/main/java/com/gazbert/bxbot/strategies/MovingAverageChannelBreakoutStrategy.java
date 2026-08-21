/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Gareth Jon Lynch
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.strategy.api.TradingStrategy;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.OpenOrder;
import com.gazbert.bxbot.trading.api.OrderType;
import com.gazbert.bxbot.trading.api.Ticker;
import com.gazbert.bxbot.trading.api.TradingApi;
import com.gazbert.bxbot.trading.api.TradingApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 * A trading strategy that combines EMA (Exponential Moving Average) crossover as a trend filter
 * with Donchian channel breakout as an entry/exit signal.
 *
 * <p><strong>Algorithm overview:</strong>
 *
 * <ul>
 *   <li>A <em>short EMA</em> and a <em>long EMA</em> are maintained on the rolling price history.
 *   <li>A <em>Donchian channel</em> tracks the highest and lowest prices over the last
 *       {@code channel-period} ticks.
 *   <li><strong>BUY signal:</strong> shortEMA &gt; longEMA <em>and</em> current price &ge; upper
 *       channel bound (uptrend breakout).
 *   <li><strong>SELL signal:</strong> shortEMA &lt; longEMA <em>and</em> current price &le; lower
 *       channel bound (downtrend breakout).
 *   <li>A configurable <strong>stop-loss</strong> closes the position if the price falls more than
 *       {@code stop-loss-percentage}% below the entry price.
 * </ul>
 *
 * <p>Because the bxbot Trading API provides only the current price (no historical OHLCV), price
 * history is built incrementally — one tick per {@link #execute()} call. The strategy enters a
 * silent warm-up phase until the history buffer contains at least
 * {@code max(long-ema-period, channel-period)} prices.
 *
 * <p><strong>Test mode</strong> ({@code test-mode: true} in strategies.yaml): no real orders are
 * submitted to the exchange. Instead, every trade decision is logged with a {@code [TEST MODE]}
 * prefix and the position transitions as if the order filled instantly. This lets you evaluate the
 * algorithm against live market data without financial risk.
 *
 * <p><strong>DISCLAIMER:</strong> This strategy is provided as-is. Use at your own risk.
 *
 * <p>Configure via {@code config/strategies.yaml}:
 *
 * <pre>{@code
 * - id: ma-channel-breakout-strategy
 *   beanName: movingAverageChannelBreakout
 *   configItems:
 *     counter-currency-buy-order-amount: 20
 *     short-ema-period: 10
 *     long-ema-period: 20
 *     channel-period: 20
 *     stop-loss-percentage: 2.0
 *     test-mode: true
 * }</pre>
 *
 * @author luca valensisi
 */
@Component("movingAverageChannelBreakout")
@Log4j2
public class MovingAverageChannelBreakoutStrategy implements TradingStrategy {

  private static final String DECIMAL_FORMAT = "#.########";
  private static final int SCALE = 8;

  /** Position state machine. */
  private enum PositionState {
    FLAT,
    WAITING_BUY_FILL,
    LONG,
    WAITING_SELL_FILL
  }

  // ---- injected by init() ----
  private TradingApi tradingApi;
  private Market market;

  // ---- config ----
  private BigDecimal counterCurrencyBuyOrderAmount;
  private int shortEmaPeriod;
  private int longEmaPeriod;
  private int channelPeriod;
  private BigDecimal stopLossPercentage;
  private boolean testMode;

  // ---- indicator state ----
  private final Deque<BigDecimal> priceHistory = new ArrayDeque<>();
  private int warmUpSize;
  private BigDecimal shortEmaValue;
  private BigDecimal longEmaValue;

  // ---- position state ----
  private PositionState positionState = PositionState.FLAT;
  private String activeOrderId;
  private BigDecimal activeOrderAmount;
  private BigDecimal entryPrice;

  // ---- test-mode counter ----
  private long simulatedOrderCounter = 0;

  /** Public no-arg constructor required for className-based instantiation. */
  public MovingAverageChannelBreakoutStrategy() {
    // No extra init.
  }

  /**
   * Initializes the strategy. Called once by the Trading Engine at startup.
   *
   * @param tradingApi the Trading API
   * @param market the market this strategy trades on
   * @param config strategy configuration from strategies.yaml
   */
  @Override
  public void init(TradingApi tradingApi, Market market, StrategyConfig config) {
    log.info("Initialising Moving Average Channel Breakout Strategy...");
    this.tradingApi = tradingApi;
    this.market = market;
    loadConfig(config);
    warmUpSize = Math.max(longEmaPeriod, channelPeriod);
    log.info(
        "Strategy initialised. shortEmaPeriod={}, longEmaPeriod={}, channelPeriod={}, "
            + "stopLoss={}%, testMode={}, warmUpSize={}",
        shortEmaPeriod,
        longEmaPeriod,
        channelPeriod,
        stopLossPercentage,
        testMode,
        warmUpSize);
  }

  /**
   * Main execution method called by the Trading Engine on every trade cycle.
   *
   * @throws StrategyException if a non-recoverable error occurs
   */
  @Override
  public void execute() throws StrategyException {
    log.info("{} execute() called. positionState={}", market.getName(), positionState);

    try {
      final BigDecimal currentPrice = fetchCurrentPrice();
      if (currentPrice == null) {
        log.warn("{} Ticker returned null last price — skipping cycle.", market.getName());
        return;
      }

      updateIndicators(currentPrice);

      if (!isWarmUpComplete()) {
        log.info(
            "{} Warm-up phase: {}/{} ticks collected. No trading yet.",
            market.getName(),
            priceHistory.size(),
            warmUpSize);
        return;
      }

      logIndicators(currentPrice);

      switch (positionState) {
        case FLAT -> handleFlat(currentPrice);
        case WAITING_BUY_FILL -> handleWaitingBuyFill(currentPrice);
        case LONG -> handleLong(currentPrice);
        case WAITING_SELL_FILL -> handleWaitingSellFill(currentPrice);
        default -> log.warn("{} Unknown positionState: {}", market.getName(), positionState);
      }

    } catch (ExchangeNetworkException e) {
      log.error(
          "{} Exchange network error — waiting for next cycle.", market.getName(), e);

    } catch (TradingApiException e) {
      log.error(
          "{} TradingApi error — telling Trading Engine to shut down.", market.getName(), e);
      throw new StrategyException(e);
    }
  }

  // ---------------------------------------------------------------------------
  // State handlers
  // ---------------------------------------------------------------------------

  private void handleFlat(BigDecimal currentPrice)
      throws TradingApiException, ExchangeNetworkException, StrategyException {
    if (isBuySignal(currentPrice)) {
      log.info(
          "{} BUY signal detected. shortEMA={} > longEMA={} AND price={} >= upperChannel={}",
          market.getName(),
          fmt(shortEmaValue),
          fmt(longEmaValue),
          fmt(currentPrice),
          fmt(getUpperChannel()));

      final BigDecimal amount = getAmountOfBaseCurrencyToBuy(counterCurrencyBuyOrderAmount);
      activeOrderId = placeBuyOrder(currentPrice, amount);
      activeOrderAmount = amount;

      if (testMode) {
        // Simulate instant fill
        entryPrice = currentPrice;
        positionState = PositionState.LONG;
        log.info(
            "{} [TEST MODE] Simulated BUY filled immediately at {}.",
            market.getName(),
            fmt(entryPrice));
      } else {
        positionState = PositionState.WAITING_BUY_FILL;
      }
    } else {
      log.info("{} No BUY signal — staying FLAT.", market.getName());
    }
  }

  private void handleWaitingBuyFill(BigDecimal currentPrice)
      throws TradingApiException, ExchangeNetworkException {
    if (isOrderFilled(activeOrderId)) {
      entryPrice = currentPrice;
      positionState = PositionState.LONG;
      log.info(
          "{} BUY order {} filled. Entry price recorded as {}.",
          market.getName(),
          activeOrderId,
          fmt(entryPrice));
    } else {
      log.info(
          "{} BUY order {} still open — waiting for fill.", market.getName(), activeOrderId);
    }
  }

  private void handleLong(BigDecimal currentPrice)
      throws TradingApiException, ExchangeNetworkException, StrategyException {
    if (isStopLossTriggered(currentPrice)) {
      log.warn(
          "{} STOP LOSS triggered! currentPrice={} < stopLevel={}. Closing position.",
          market.getName(),
          fmt(currentPrice),
          fmt(stopLossLevel()));
      closePosition(currentPrice);
      return;
    }

    if (isSellSignal(currentPrice)) {
      log.info(
          "{} SELL signal detected. shortEMA={} < longEMA={} AND price={} <= lowerChannel={}",
          market.getName(),
          fmt(shortEmaValue),
          fmt(longEmaValue),
          fmt(currentPrice),
          fmt(getLowerChannel()));
      closePosition(currentPrice);
    } else {
      log.info(
          "{} Holding LONG position. entryPrice={}, currentPrice={}.",
          market.getName(),
          fmt(entryPrice),
          fmt(currentPrice));
    }
  }

  private void handleWaitingSellFill(BigDecimal currentPrice)
      throws TradingApiException, ExchangeNetworkException {
    if (isOrderFilled(activeOrderId)) {
      positionState = PositionState.FLAT;
      activeOrderId = null;
      activeOrderAmount = null;
      entryPrice = null;
      log.info(
          "{} SELL order filled. Position closed. Now FLAT.",
          market.getName());
    } else {
      log.info(
          "{} SELL order {} still open — waiting for fill.", market.getName(), activeOrderId);
    }
  }

  // ---------------------------------------------------------------------------
  // Order helpers
  // ---------------------------------------------------------------------------

  /**
   * Places a BUY limit order or, in test mode, logs the simulated order.
   *
   * @param price limit price
   * @param amount base currency amount
   * @return order ID (real or simulated)
   */
  private String placeBuyOrder(BigDecimal price, BigDecimal amount)
      throws TradingApiException, ExchangeNetworkException {
    if (testMode) {
      final String fakeId = "TEST-BUY-" + ++simulatedOrderCounter;
      log.info(
          "{} [TEST MODE] WOULD PLACE BUY order: {} {} at {} {}. orderId={}",
          market.getName(),
          fmt(amount),
          market.getBaseCurrency(),
          fmt(price),
          market.getCounterCurrency(),
          fakeId);
      return fakeId;
    }
    log.info(
        "{} Placing BUY order: {} {} at {} {}.",
        market.getName(),
        fmt(amount),
        market.getBaseCurrency(),
        fmt(price),
        market.getCounterCurrency());
    final String orderId = tradingApi.createOrder(market.getId(), OrderType.BUY, amount, price);
    log.info("{} BUY order placed. orderId={}", market.getName(), orderId);
    return orderId;
  }

  /**
   * Places a SELL limit order or, in test mode, logs the simulated order.
   *
   * @param price limit price
   * @param amount base currency amount
   * @return order ID (real or simulated)
   */
  private String placeSellOrder(BigDecimal price, BigDecimal amount)
      throws TradingApiException, ExchangeNetworkException {
    if (testMode) {
      final String fakeId = "TEST-SELL-" + ++simulatedOrderCounter;
      log.info(
          "{} [TEST MODE] WOULD PLACE SELL order: {} {} at {} {}. orderId={}",
          market.getName(),
          fmt(amount),
          market.getBaseCurrency(),
          fmt(price),
          market.getCounterCurrency(),
          fakeId);
      return fakeId;
    }
    log.info(
        "{} Placing SELL order: {} {} at {} {}.",
        market.getName(),
        fmt(amount),
        market.getBaseCurrency(),
        fmt(price),
        market.getCounterCurrency());
    final String orderId = tradingApi.createOrder(market.getId(), OrderType.SELL, amount, price);
    log.info("{} SELL order placed. orderId={}", market.getName(), orderId);
    return orderId;
  }

  private void closePosition(BigDecimal currentPrice)
      throws TradingApiException, ExchangeNetworkException, StrategyException {
    activeOrderId = placeSellOrder(currentPrice, activeOrderAmount);

    if (testMode) {
      // Simulate instant fill
      positionState = PositionState.FLAT;
      activeOrderId = null;
      activeOrderAmount = null;
      entryPrice = null;
      log.info("{} [TEST MODE] Simulated SELL filled immediately at {}. Now FLAT.",
          market.getName(), fmt(currentPrice));
    } else {
      positionState = PositionState.WAITING_SELL_FILL;
    }
  }

  /**
   * Returns true if the given order ID is no longer present in the exchange's open orders list,
   * meaning it has been fully filled (or cancelled).
   */
  private boolean isOrderFilled(String orderId)
      throws TradingApiException, ExchangeNetworkException {
    final List<OpenOrder> openOrders = tradingApi.getYourOpenOrders(market.getId());
    for (final OpenOrder order : openOrders) {
      if (order.getId().equals(orderId)) {
        return false;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------------------
  // Signal logic
  // ---------------------------------------------------------------------------

  private boolean isBuySignal(BigDecimal currentPrice) {
    return shortEmaValue.compareTo(longEmaValue) > 0
        && currentPrice.compareTo(getUpperChannel()) >= 0;
  }

  private boolean isSellSignal(BigDecimal currentPrice) {
    return shortEmaValue.compareTo(longEmaValue) < 0
        && currentPrice.compareTo(getLowerChannel()) <= 0;
  }

  private boolean isStopLossTriggered(BigDecimal currentPrice) {
    return currentPrice.compareTo(stopLossLevel()) < 0;
  }

  private BigDecimal stopLossLevel() {
    final BigDecimal factor =
        BigDecimal.ONE.subtract(
            stopLossPercentage.divide(new BigDecimal("100"), SCALE, RoundingMode.HALF_UP));
    return entryPrice.multiply(factor).setScale(SCALE, RoundingMode.HALF_UP);
  }

  // ---------------------------------------------------------------------------
  // Indicator helpers
  // ---------------------------------------------------------------------------

  /**
   * Adds {@code currentPrice} to the rolling history and recomputes both EMAs.
   *
   * <p>The Deque acts as a sliding window: the oldest entry is evicted once the buffer exceeds
   * {@code warmUpSize} elements. EMA initialisation uses SMA over the first {@code period} prices;
   * subsequent values use the standard incremental formula.
   */
  private void updateIndicators(BigDecimal currentPrice) {
    priceHistory.addLast(currentPrice);
    if (priceHistory.size() > warmUpSize) {
      priceHistory.pollFirst();
    }

    final int size = priceHistory.size();
    if (size < longEmaPeriod) {
      // Not enough data for long EMA — reset both to null
      shortEmaValue = null;
      longEmaValue = null;
      return;
    }

    // Build an array snapshot for EMA calculation
    final BigDecimal[] prices = priceHistory.toArray(new BigDecimal[0]);

    if (shortEmaValue == null) {
      shortEmaValue = sma(prices, shortEmaPeriod);
    } else {
      shortEmaValue = ema(currentPrice, shortEmaValue, shortEmaPeriod);
    }

    if (longEmaValue == null) {
      longEmaValue = sma(prices, longEmaPeriod);
    } else {
      longEmaValue = ema(currentPrice, longEmaValue, longEmaPeriod);
    }
  }

  private boolean isWarmUpComplete() {
    return priceHistory.size() >= warmUpSize && shortEmaValue != null && longEmaValue != null;
  }

  /** Computes EMA for the next tick using the incremental formula. */
  private static BigDecimal ema(BigDecimal price, BigDecimal prevEma, int period) {
    // multiplier = 2 / (period + 1)
    final BigDecimal multiplier =
        new BigDecimal(2).divide(new BigDecimal(period + 1), SCALE, RoundingMode.HALF_UP);
    final BigDecimal oneMinusMultiplier = BigDecimal.ONE.subtract(multiplier);
    return price.multiply(multiplier).add(prevEma.multiply(oneMinusMultiplier))
        .setScale(SCALE, RoundingMode.HALF_UP);
  }

  /** Computes the Simple Moving Average of the last {@code period} entries in {@code prices}. */
  private static BigDecimal sma(BigDecimal[] prices, int period) {
    BigDecimal sum = BigDecimal.ZERO;
    final int start = prices.length - period;
    for (int i = start; i < prices.length; i++) {
      sum = sum.add(prices[i]);
    }
    return sum.divide(new BigDecimal(period), SCALE, RoundingMode.HALF_UP);
  }

  /** Returns the highest price in the last {@code channelPeriod} entries. */
  private BigDecimal getUpperChannel() {
    BigDecimal max = null;
    int i = 0;
    final int start = priceHistory.size() - channelPeriod;
    for (final BigDecimal price : priceHistory) {
      if (i++ < start) {
        continue;
      }
      if (max == null || price.compareTo(max) > 0) {
        max = price;
      }
    }
    return max;
  }

  /** Returns the lowest price in the last {@code channelPeriod} entries. */
  private BigDecimal getLowerChannel() {
    BigDecimal min = null;
    int i = 0;
    final int start = priceHistory.size() - channelPeriod;
    for (final BigDecimal price : priceHistory) {
      if (i++ < start) {
        continue;
      }
      if (min == null || price.compareTo(min) < 0) {
        min = price;
      }
    }
    return min;
  }

  // ---------------------------------------------------------------------------
  // Market data
  // ---------------------------------------------------------------------------

  /**
   * Fetches the current market price via {@link TradingApi#getTicker(String)}.
   *
   * @return last trade price, or {@code null} if ticker is unavailable
   */
  private BigDecimal fetchCurrentPrice() throws TradingApiException, ExchangeNetworkException {
    final Ticker ticker = tradingApi.getTicker(market.getId());
    if (ticker == null) {
      return null;
    }
    return ticker.getLast();
  }

  /**
   * Calculates the amount of base currency to buy for a given counter currency amount, based on the
   * latest market price.
   *
   * @param counterAmount amount of counter currency to spend
   * @return base currency amount rounded to 8 decimal places
   */
  private BigDecimal getAmountOfBaseCurrencyToBuy(BigDecimal counterAmount)
      throws TradingApiException, ExchangeNetworkException {
    final BigDecimal lastPrice = tradingApi.getLatestMarketPrice(market.getId());
    log.info(
        "{} Latest market price for 1 {} = {} {}",
        market.getName(),
        market.getBaseCurrency(),
        fmt(lastPrice),
        market.getCounterCurrency());
    final BigDecimal amount = counterAmount.divide(lastPrice, SCALE, RoundingMode.HALF_DOWN);
    log.info(
        "{} Buying {} {} for {} {}",
        market.getName(),
        amount,
        market.getBaseCurrency(),
        fmt(counterAmount),
        market.getCounterCurrency());
    return amount;
  }

  // ---------------------------------------------------------------------------
  // Config loading
  // ---------------------------------------------------------------------------

  private void loadConfig(StrategyConfig config) {
    counterCurrencyBuyOrderAmount =
        requireDecimal(config, "counter-currency-buy-order-amount");
    shortEmaPeriod = requirePositiveInt(config, "short-ema-period");
    longEmaPeriod = requirePositiveInt(config, "long-ema-period");
    channelPeriod = requirePositiveInt(config, "channel-period");
    stopLossPercentage = requireDecimal(config, "stop-loss-percentage");
    testMode = Boolean.parseBoolean(requireString(config, "test-mode"));

    if (shortEmaPeriod >= longEmaPeriod) {
      throw new IllegalArgumentException(
          "short-ema-period (" + shortEmaPeriod + ") must be less than long-ema-period ("
              + longEmaPeriod + ")");
    }
  }

  private static String requireString(StrategyConfig config, String key) {
    final String value = config.getConfigItem(key);
    if (value == null) {
      throw new IllegalArgumentException("Mandatory config item missing: " + key);
    }
    return value;
  }

  private static BigDecimal requireDecimal(StrategyConfig config, String key) {
    final String raw = requireString(config, key);
    try {
      return new BigDecimal(raw);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Config item '" + key + "' is not a valid decimal: " + raw, e);
    }
  }

  private static int requirePositiveInt(StrategyConfig config, String key) {
    final String raw = requireString(config, key);
    try {
      final int value = Integer.parseInt(raw);
      if (value <= 0) {
        throw new IllegalArgumentException(
            "Config item '" + key + "' must be positive, got: " + value);
      }
      return value;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Config item '" + key + "' is not a valid integer: " + raw, e);
    }
  }

  // ---------------------------------------------------------------------------
  // Formatting / logging
  // ---------------------------------------------------------------------------

  private static String fmt(BigDecimal value) {
    if (value == null) {
      return "null";
    }
    return new DecimalFormat(DECIMAL_FORMAT).format(value);
  }

  private void logIndicators(BigDecimal currentPrice) {
    log.info(
        "{} Indicators — price={}, shortEMA={}, longEMA={}, upperChannel={}, lowerChannel={}",
        market.getName(),
        fmt(currentPrice),
        fmt(shortEmaValue),
        fmt(longEmaValue),
        fmt(getUpperChannel()),
        fmt(getLowerChannel()));
  }
}
