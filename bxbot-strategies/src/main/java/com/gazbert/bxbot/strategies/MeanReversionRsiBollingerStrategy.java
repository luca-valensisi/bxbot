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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 * A mean reversion trading strategy that combines Bollinger Bands and the RSI, gated by an ADX
 * market regime filter, with ATR-based position sizing and stop-loss.
 *
 * <p><strong>Algorithm overview:</strong>
 *
 * <ul>
 *   <li><strong>ENTRY (long only):</strong> the market is <em>not</em> trending
 *       ({@code ADX < adx-trend-threshold}) <em>and</em> the last close is at or below the lower
 *       Bollinger band <em>and</em> {@code RSI < rsi-oversold-threshold}. Entries are only
 *       evaluated when a candle closes.
 *   <li><strong>EXIT:</strong> whichever comes first - price reaches the middle band (the SMA), or
 *       {@code RSI > rsi-exit-threshold}, or the ATR stop-loss is hit, or the position has been
 *       held for {@code max-holding-candles} candles. Exits are evaluated on every tick so the
 *       stop-loss reacts intra-candle.
 *   <li><strong>RISK:</strong> position size is
 *       {@code (equity * risk-per-trade-percentage / 100) / (ATR * atr-stop-loss-multiplier)},
 *       capped at {@code counter-currency-buy-order-amount}. A drawdown of more than
 *       {@code max-drawdown-percentage} from the peak equity halts the strategy.
 * </ul>
 *
 * <p><strong>Candles.</strong> The bxbot Trading API exposes no historical OHLCV data, so candles
 * are aggregated in memory from the ticker: every {@link #execute()} call samples the last price
 * and folds it into the candle for the current {@code candle-seconds} bucket. Indicators are
 * recomputed when a bucket rolls over. This means a silent warm-up phase of
 * {@code max(bollinger-period, rsi-period + 1, atr-period + 1, adx-period * 2 + 1)} closed candles
 * before the first signal - roughly 28 hours with 1h candles and {@code adx-period: 14}. Use a
 * smaller {@code candle-seconds} for a faster dry-run.
 *
 * <p><strong>Short entries are not implemented.</strong> BX-bot is spot only: {@code OrderType}
 * offers just BUY and SELL, and every shipped Exchange Adapter submits limit orders. The overbought
 * mirror condition is therefore only logged, as {@code [SHORT SIGNAL - NOT TRADED]}, so it can
 * still be evaluated from the logs.
 *
 * <p><strong>Test mode</strong> ({@code test-mode: true} in strategies.yaml): no real orders are
 * submitted to the exchange. Every trade decision is logged with a {@code [TEST MODE]} prefix and
 * the position transitions as if the order filled instantly. The simulated P&amp;L ledger still
 * runs, so the drawdown kill switch is exercised - but it only halts this strategy instead of
 * shutting the bot down.
 *
 * <p><strong>State is held in memory only.</strong> A restart loses the candle history, the open
 * position and the drawdown tracking.
 *
 * <p><strong>DISCLAIMER:</strong> This strategy is provided as-is and is not financial advice. Use
 * at your own risk.
 *
 * <p>Configure via {@code config/strategies.yaml}:
 *
 * <pre>{@code
 * - id: mean-reversion-rsi-bollinger-strategy
 *   beanName: meanReversionRsiBollinger
 *   configItems:
 *     candle-seconds: 300
 *     bollinger-period: 20
 *     bollinger-std-dev-multiplier: 2.0
 *     rsi-period: 14
 *     rsi-oversold-threshold: 30
 *     rsi-overbought-threshold: 70
 *     rsi-exit-threshold: 50
 *     atr-period: 14
 *     atr-stop-loss-multiplier: 2.0
 *     adx-period: 14
 *     adx-trend-threshold: 25
 *     max-holding-candles: 24
 *     starting-capital: 1000
 *     risk-per-trade-percentage: 1.0
 *     counter-currency-buy-order-amount: 20
 *     max-drawdown-percentage: 15.0
 *     test-mode: true
 * }</pre>
 *
 * @author luca valensisi
 */
@Component("meanReversionRsiBollinger")
@Log4j2
public class MeanReversionRsiBollingerStrategy implements TradingStrategy {

  private static final String DECIMAL_FORMAT = "#.########";
  private static final int SCALE = 8;
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final MathContext SQRT_CONTEXT =
      new MathContext(SCALE + 2, RoundingMode.HALF_UP);

  /** Timestamps above this are milliseconds; below, they are seconds. */
  private static final long MILLIS_THRESHOLD = 100_000_000_000L;

  /** Position state machine. */
  private enum PositionState {
    FLAT,
    WAITING_BUY_FILL,
    LONG,
    WAITING_SELL_FILL,
    HALTED
  }

  /** An immutable OHLC bar aggregated from ticker samples. */
  private static final class Candle {

    private final BigDecimal open;
    private final BigDecimal high;
    private final BigDecimal low;
    private final BigDecimal close;

    Candle(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
      this.open = open;
      this.high = high;
      this.low = low;
      this.close = close;
    }
  }

  // ---- injected by init() ----
  private TradingApi tradingApi;
  private Market market;

  // ---- config ----
  private int candleSeconds;
  private int bollingerPeriod;
  private BigDecimal bollingerStdDevMultiplier;
  private int rsiPeriod;
  private BigDecimal rsiOversoldThreshold;
  private BigDecimal rsiOverboughtThreshold;
  private BigDecimal rsiExitThreshold;
  private int atrPeriod;
  private BigDecimal atrStopLossMultiplier;
  private int adxPeriod;
  private BigDecimal adxTrendThreshold;
  private int maxHoldingCandles;
  private BigDecimal startingCapital;
  private BigDecimal riskPerTradePercentage;
  private BigDecimal counterCurrencyBuyOrderAmount;
  private BigDecimal maxDrawdownPercentage;
  private boolean testMode;

  // ---- candle aggregation state ----
  private final Deque<Candle> candleHistory = new ArrayDeque<>();
  private int historyCapacity;
  private int warmUpCandles;
  private long currentBucket = -1L;
  private Candle currentCandle;
  private long closedCandleCount;

  // ---- indicator state (recomputed on each candle close) ----
  private BigDecimal middleBand;
  private BigDecimal upperBand;
  private BigDecimal lowerBand;
  private BigDecimal rsiValue;
  private BigDecimal atrValue;
  private BigDecimal adxValue;

  // ---- position state ----
  private PositionState positionState = PositionState.FLAT;
  private String activeOrderId;
  private BigDecimal activeOrderAmount;
  private BigDecimal entryPrice;
  private BigDecimal entryStopDistance;
  private BigDecimal stopLossPrice;
  private long entryCandleCount;

  // ---- risk ledger ----
  private BigDecimal realizedPnl = BigDecimal.ZERO;
  private BigDecimal peakEquity;
  private BigDecimal buyFeeFraction;
  private BigDecimal sellFeeFraction;
  private boolean feesLoaded;

  // ---- test-mode counter ----
  private long simulatedOrderCounter;

  /** Public no-arg constructor required for className-based instantiation. */
  public MeanReversionRsiBollingerStrategy() {
    // No extra init.
  }

  /**
   * Initialises the strategy. Called once by the Trading Engine at startup.
   *
   * @param tradingApi the Trading API
   * @param market the market this strategy trades on
   * @param config strategy configuration from strategies.yaml
   */
  @Override
  public void init(TradingApi tradingApi, Market market, StrategyConfig config) {
    log.info("Initialising Mean Reversion RSI/Bollinger Strategy...");
    this.tradingApi = tradingApi;
    this.market = market;
    loadConfig(config);

    warmUpCandles =
        Math.max(
            bollingerPeriod,
            Math.max(Math.max(rsiPeriod + 1, atrPeriod + 1), adxPeriod * 2 + 1));
    historyCapacity = warmUpCandles;
    peakEquity = startingCapital;

    log.info(
        "Strategy initialised. candleSeconds={}, bollinger={}/{}, rsi={} (oversold={}, exit={}, "
            + "overbought={}), atr={} x{}, adx={} (threshold={}), maxHoldingCandles={}, "
            + "startingCapital={}, riskPerTrade={}%, maxDrawdown={}%, testMode={}, "
            + "warmUpCandles={}",
        candleSeconds,
        bollingerPeriod,
        bollingerStdDevMultiplier,
        rsiPeriod,
        rsiOversoldThreshold,
        rsiExitThreshold,
        rsiOverboughtThreshold,
        atrPeriod,
        atrStopLossMultiplier,
        adxPeriod,
        adxTrendThreshold,
        maxHoldingCandles,
        startingCapital,
        riskPerTradePercentage,
        maxDrawdownPercentage,
        testMode,
        warmUpCandles);
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
      final Ticker ticker = tradingApi.getTicker(market.getId());
      final BigDecimal currentPrice = ticker == null ? null : ticker.getLast();
      if (currentPrice == null) {
        log.warn("{} Ticker returned no last price - skipping cycle.", market.getName());
        return;
      }

      final boolean candleClosed = updateCandles(currentPrice, ticker.getTimestamp());
      if (candleClosed) {
        recomputeIndicators();
      }

      if (positionState == PositionState.HALTED) {
        log.warn(
            "{} Strategy is HALTED after breaching the max drawdown limit. Manual intervention "
                + "required - no further trading.",
            market.getName());
        return;
      }

      if (!isWarmUpComplete()) {
        log.info(
            "{} Warm-up phase: {}/{} candles closed. No trading yet.",
            market.getName(),
            closedCandleCount,
            warmUpCandles);
        return;
      }

      if (candleClosed) {
        logIndicators(currentPrice);
        logShortSignalIfPresent();
      }

      switch (positionState) {
        case FLAT -> {
          if (candleClosed) {
            handleFlat(currentPrice);
          }
        }
        case WAITING_BUY_FILL -> handleWaitingBuyFill(currentPrice);
        case LONG -> handleLong(currentPrice);
        case WAITING_SELL_FILL -> handleWaitingSellFill(currentPrice);
        default -> log.warn("{} Unknown positionState: {}", market.getName(), positionState);
      }

    } catch (ExchangeNetworkException e) {
      log.error("{} Exchange network error - waiting for next cycle.", market.getName(), e);

    } catch (TradingApiException e) {
      log.error("{} TradingApi error - telling Trading Engine to shut down.", market.getName(), e);
      throw new StrategyException(e);
    }
  }

  // ---------------------------------------------------------------------------
  // State handlers
  // ---------------------------------------------------------------------------

  private void handleFlat(BigDecimal currentPrice)
      throws TradingApiException, ExchangeNetworkException {
    if (!isEntrySignal()) {
      log.info(
          "{} No ENTRY signal - staying FLAT. adx={} (threshold={}), close={} (lowerBand={}), "
              + "rsi={} (oversold={})",
          market.getName(),
          fmt(adxValue),
          fmt(adxTrendThreshold),
          fmt(lastClose()),
          fmt(lowerBand),
          fmt(rsiValue),
          fmt(rsiOversoldThreshold));
      return;
    }

    log.info(
        "{} ENTRY signal detected. close={} <= lowerBand={} AND rsi={} < {} AND adx={} < {} "
            + "(ranging market).",
        market.getName(),
        fmt(lastClose()),
        fmt(lowerBand),
        fmt(rsiValue),
        fmt(rsiOversoldThreshold),
        fmt(adxValue),
        fmt(adxTrendThreshold));

    loadExchangeFees();

    final BigDecimal stopDistance =
        atrValue.multiply(atrStopLossMultiplier).setScale(SCALE, RoundingMode.HALF_UP);
    if (stopDistance.signum() <= 0) {
      log.warn(
          "{} ATR stop distance is {} - cannot size the position safely. Skipping entry.",
          market.getName(),
          fmt(stopDistance));
      return;
    }

    final BigDecimal quantity = calculatePositionSize(currentPrice, stopDistance);
    if (quantity == null) {
      return;
    }

    activeOrderId = placeBuyOrder(currentPrice, quantity);
    activeOrderAmount = quantity;
    entryPrice = currentPrice;
    entryStopDistance = stopDistance;
    stopLossPrice = currentPrice.subtract(stopDistance);
    entryCandleCount = closedCandleCount;

    if (testMode) {
      positionState = PositionState.LONG;
      log.info(
          "{} [TEST MODE] Simulated BUY filled immediately at {}. stopLoss={}",
          market.getName(),
          fmt(entryPrice),
          fmt(stopLossPrice));
    } else {
      positionState = PositionState.WAITING_BUY_FILL;
    }
  }

  private void handleWaitingBuyFill(BigDecimal currentPrice)
      throws TradingApiException, ExchangeNetworkException {
    if (!isOrderFilled(activeOrderId)) {
      log.info("{} BUY order {} still open - waiting for fill.", market.getName(), activeOrderId);
      return;
    }
    entryPrice = currentPrice;
    stopLossPrice = entryPrice.subtract(entryStopDistance);
    entryCandleCount = closedCandleCount;
    positionState = PositionState.LONG;
    log.info(
        "{} BUY order {} filled. entryPrice={}, stopLoss={}",
        market.getName(),
        activeOrderId,
        fmt(entryPrice),
        fmt(stopLossPrice));
  }

  private void handleLong(BigDecimal currentPrice)
      throws TradingApiException, ExchangeNetworkException, StrategyException {
    final String exitReason = findExitReason(currentPrice);
    if (exitReason == null) {
      log.info(
          "{} Holding LONG. entryPrice={}, currentPrice={}, stopLoss={}, target(SMA)={}, rsi={}, "
              + "candlesHeld={}/{}",
          market.getName(),
          fmt(entryPrice),
          fmt(currentPrice),
          fmt(stopLossPrice),
          fmt(middleBand),
          fmt(rsiValue),
          closedCandleCount - entryCandleCount,
          maxHoldingCandles);
      return;
    }

    log.info("{} EXIT signal: {}. Closing position.", market.getName(), exitReason);
    closePosition(currentPrice);
  }

  private void handleWaitingSellFill(BigDecimal currentPrice)
      throws TradingApiException, ExchangeNetworkException, StrategyException {
    if (!isOrderFilled(activeOrderId)) {
      log.info("{} SELL order {} still open - waiting for fill.", market.getName(), activeOrderId);
      return;
    }
    log.info("{} SELL order {} filled.", market.getName(), activeOrderId);
    settleTrade(currentPrice);
  }

  // ---------------------------------------------------------------------------
  // Signal logic
  // ---------------------------------------------------------------------------

  /** Entry needs a ranging market, price on the lower band and an oversold RSI. */
  private boolean isEntrySignal() {
    return adxValue.compareTo(adxTrendThreshold) < 0
        && lastClose().compareTo(lowerBand) <= 0
        && rsiValue.compareTo(rsiOversoldThreshold) < 0;
  }

  /**
   * Returns a human readable reason to close the position, or {@code null} to keep holding.
   *
   * <p>The stop-loss is checked first so it always wins, and it is evaluated against the current
   * tick price rather than the last close.
   */
  private String findExitReason(BigDecimal currentPrice) {
    if (currentPrice.compareTo(stopLossPrice) <= 0) {
      return "STOP LOSS - price " + fmt(currentPrice) + " <= stop " + fmt(stopLossPrice);
    }
    if (currentPrice.compareTo(middleBand) >= 0) {
      return "TARGET - price " + fmt(currentPrice) + " reverted to the SMA " + fmt(middleBand);
    }
    if (rsiValue.compareTo(rsiExitThreshold) > 0) {
      return "RSI - " + fmt(rsiValue) + " > exit threshold " + fmt(rsiExitThreshold);
    }
    if (closedCandleCount - entryCandleCount >= maxHoldingCandles) {
      return "TIME EXIT - held for " + (closedCandleCount - entryCandleCount) + " candles";
    }
    return null;
  }

  /**
   * Logs the mirror short setup. BX-bot is spot only, so it is never traded - see the class
   * javadoc.
   */
  private void logShortSignalIfPresent() {
    if (lastClose().compareTo(upperBand) >= 0
        && rsiValue.compareTo(rsiOverboughtThreshold) > 0) {
      log.info(
          "{} [SHORT SIGNAL - NOT TRADED] close={} >= upperBand={} AND rsi={} > {}. Spot only: "
              + "no short position is opened.",
          market.getName(),
          fmt(lastClose()),
          fmt(upperBand),
          fmt(rsiValue),
          fmt(rsiOverboughtThreshold));
    }
  }

  // ---------------------------------------------------------------------------
  // Risk management
  // ---------------------------------------------------------------------------

  private BigDecimal equity() {
    return startingCapital.add(realizedPnl);
  }

  /**
   * Sizes the position so that a stop-loss hit costs {@code risk-per-trade-percentage} of equity,
   * capped by {@code counter-currency-buy-order-amount}.
   *
   * @return the base currency quantity to buy, or {@code null} if no safe size could be computed
   */
  private BigDecimal calculatePositionSize(BigDecimal currentPrice, BigDecimal stopDistance) {
    final BigDecimal equity = equity();
    if (equity.signum() <= 0) {
      log.warn("{} Equity is {} - refusing to open a position.", market.getName(), fmt(equity));
      return null;
    }

    final BigDecimal riskAmount =
        equity.multiply(riskPerTradePercentage).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
    BigDecimal quantity = riskAmount.divide(stopDistance, SCALE, RoundingMode.HALF_DOWN);

    final BigDecimal notionalCap = counterCurrencyBuyOrderAmount.min(equity);
    final BigDecimal notional = quantity.multiply(currentPrice);
    if (notional.compareTo(notionalCap) > 0) {
      quantity = notionalCap.divide(currentPrice, SCALE, RoundingMode.HALF_DOWN);
      log.info(
          "{} Risk-based notional {} exceeds the cap {} - size reduced to {} {}.",
          market.getName(),
          fmt(notional),
          fmt(notionalCap),
          fmt(quantity),
          market.getBaseCurrency());
    }

    if (quantity.signum() <= 0) {
      log.warn("{} Computed order quantity is {} - skipping entry.", market.getName(), quantity);
      return null;
    }

    log.info(
        "{} Position size: {} {} (equity={}, risk={}%, riskAmount={}, stopDistance={}).",
        market.getName(),
        fmt(quantity),
        market.getBaseCurrency(),
        fmt(equity),
        fmt(riskPerTradePercentage),
        fmt(riskAmount),
        fmt(stopDistance));
    return quantity;
  }

  /**
   * Books the closed trade into the simulated P&amp;L ledger, logs the trade summary and runs the
   * drawdown kill switch.
   */
  private void settleTrade(BigDecimal exitPrice) throws StrategyException {
    final BigDecimal gross = exitPrice.subtract(entryPrice).multiply(activeOrderAmount);
    final BigDecimal buyFee = entryPrice.multiply(activeOrderAmount).multiply(buyFeeFraction);
    final BigDecimal sellFee = exitPrice.multiply(activeOrderAmount).multiply(sellFeeFraction);
    final BigDecimal net =
        gross.subtract(buyFee).subtract(sellFee).setScale(SCALE, RoundingMode.HALF_UP);

    realizedPnl = realizedPnl.add(net);
    final BigDecimal equity = equity();
    if (equity.compareTo(peakEquity) > 0) {
      peakEquity = equity;
    }

    log.info(
        "{} TRADE CLOSED. entry={}, exit={}, qty={}, grossPnL={}, fees={}, netPnL={}, "
            + "cumulativePnL={}, equity={}, drawdown={}%",
        market.getName(),
        fmt(entryPrice),
        fmt(exitPrice),
        fmt(activeOrderAmount),
        fmt(gross),
        fmt(buyFee.add(sellFee)),
        fmt(net),
        fmt(realizedPnl),
        fmt(equity),
        fmt(currentDrawdownPercentage()));

    final boolean halted = checkDrawdownKillSwitch();
    clearPositionState();
    if (!halted) {
      positionState = PositionState.FLAT;
      log.info("{} Position closed. Now FLAT.", market.getName());
    }
  }

  private BigDecimal currentDrawdownPercentage() {
    if (peakEquity.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return peakEquity
        .subtract(equity())
        .multiply(HUNDRED)
        .divide(peakEquity, SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Halts the strategy if equity has fallen more than {@code max-drawdown-percentage} below its
   * peak.
   *
   * <p>In test mode this only stops this strategy, since a dry-run must not take the bot down. In
   * live mode a {@link StrategyException} is thrown so the Trading Engine logs FATAL, sends the
   * alert email and shuts down - manual intervention is then required.
   *
   * @return true if the kill switch tripped
   */
  private boolean checkDrawdownKillSwitch() throws StrategyException {
    final BigDecimal drawdown = currentDrawdownPercentage();
    if (drawdown.compareTo(maxDrawdownPercentage) < 0) {
      return false;
    }

    positionState = PositionState.HALTED;
    final String msg =
        market.getName()
            + " MAX DRAWDOWN BREACHED: "
            + fmt(drawdown)
            + "% >= "
            + fmt(maxDrawdownPercentage)
            + "% (peakEquity="
            + fmt(peakEquity)
            + ", equity="
            + fmt(equity())
            + "). Trading halted - manual intervention required.";
    log.error(msg);

    if (!testMode) {
      throw new StrategyException(msg);
    }
    log.warn("{} [TEST MODE] Bot left running; this strategy will not trade again.",
        market.getName());
    return true;
  }

  /**
   * Reads the exchange fee percentages once and caches them.
   *
   * <p>Fees only affect the simulated P&amp;L, so a failure here degrades to zero fees with a
   * warning rather than taking the bot down.
   */
  private void loadExchangeFees() {
    if (feesLoaded) {
      return;
    }
    feesLoaded = true;
    try {
      buyFeeFraction = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
      sellFeeFraction = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());
    } catch (TradingApiException | ExchangeNetworkException e) {
      log.warn(
          "{} Could not read the exchange fees - assuming zero for the simulated P&L.",
          market.getName(),
          e);
    }
    if (buyFeeFraction == null) {
      buyFeeFraction = BigDecimal.ZERO;
    }
    if (sellFeeFraction == null) {
      sellFeeFraction = BigDecimal.ZERO;
    }
    log.info(
        "{} Exchange fees - buy={}, sell={} (as decimal fractions).",
        market.getName(),
        fmt(buyFeeFraction),
        fmt(sellFeeFraction));
  }

  private void clearPositionState() {
    activeOrderId = null;
    activeOrderAmount = null;
    entryPrice = null;
    entryStopDistance = null;
    stopLossPrice = null;
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
      log.info("{} [TEST MODE] Simulated SELL filled immediately at {}.",
          market.getName(), fmt(currentPrice));
      settleTrade(currentPrice);
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
  // Candle aggregation
  // ---------------------------------------------------------------------------

  /**
   * Folds the current tick into the candle being built and rolls the bucket over when needed.
   *
   * @return true if a candle was closed by this tick
   */
  private boolean updateCandles(BigDecimal price, Long tickerTimestamp) {
    final long bucket = resolveEpochSeconds(tickerTimestamp) / candleSeconds;

    if (currentCandle == null) {
      currentBucket = bucket;
      currentCandle = new Candle(price, price, price, price);
      return false;
    }

    if (bucket == currentBucket) {
      currentCandle =
          new Candle(
              currentCandle.open,
              currentCandle.high.max(price),
              currentCandle.low.min(price),
              price);
      return false;
    }

    candleHistory.addLast(currentCandle);
    while (candleHistory.size() > historyCapacity) {
      candleHistory.pollFirst();
    }
    closedCandleCount++;
    currentBucket = bucket;
    currentCandle = new Candle(price, price, price, price);

    log.info(
        "{} Candle closed #{} - o={}, h={}, l={}, c={}",
        market.getName(),
        closedCandleCount,
        fmt(candleHistory.peekLast().open),
        fmt(candleHistory.peekLast().high),
        fmt(candleHistory.peekLast().low),
        fmt(candleHistory.peekLast().close));
    return true;
  }

  /**
   * Normalises the ticker timestamp to epoch seconds. Adapters may return {@code null}, seconds or
   * milliseconds, so the magnitude decides the unit.
   */
  private static long resolveEpochSeconds(Long tickerTimestamp) {
    if (tickerTimestamp == null) {
      return System.currentTimeMillis() / 1000L;
    }
    return tickerTimestamp > MILLIS_THRESHOLD ? tickerTimestamp / 1000L : tickerTimestamp;
  }

  private BigDecimal lastClose() {
    return candleHistory.peekLast().close;
  }

  private boolean isWarmUpComplete() {
    return closedCandleCount >= warmUpCandles
        && middleBand != null
        && upperBand != null
        && lowerBand != null
        && rsiValue != null
        && atrValue != null
        && adxValue != null;
  }

  // ---------------------------------------------------------------------------
  // Indicators
  // ---------------------------------------------------------------------------

  /** Recomputes every indicator over the rolling window of closed candles. */
  private void recomputeIndicators() {
    final List<Candle> candles = new ArrayList<>(candleHistory);

    middleBand = sma(candles, bollingerPeriod);
    if (middleBand == null) {
      upperBand = null;
      lowerBand = null;
    } else {
      final BigDecimal offset =
          standardDeviation(candles, bollingerPeriod, middleBand)
              .multiply(bollingerStdDevMultiplier)
              .setScale(SCALE, RoundingMode.HALF_UP);
      upperBand = middleBand.add(offset);
      lowerBand = middleBand.subtract(offset);
    }

    rsiValue = rsi(candles, rsiPeriod);
    atrValue = atr(candles, atrPeriod);
    adxValue = adx(candles, adxPeriod);
  }

  /** Simple moving average of the last {@code period} closes, or null if there aren't enough. */
  private static BigDecimal sma(List<Candle> candles, int period) {
    if (candles.size() < period) {
      return null;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = candles.size() - period; i < candles.size(); i++) {
      sum = sum.add(candles.get(i).close);
    }
    return sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
  }

  /** Population standard deviation of the last {@code period} closes around {@code mean}. */
  private static BigDecimal standardDeviation(
      List<Candle> candles, int period, BigDecimal mean) {
    BigDecimal sumOfSquares = BigDecimal.ZERO;
    for (int i = candles.size() - period; i < candles.size(); i++) {
      final BigDecimal diff = candles.get(i).close.subtract(mean);
      sumOfSquares = sumOfSquares.add(diff.multiply(diff));
    }
    final BigDecimal variance =
        sumOfSquares.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
    return variance.sqrt(SQRT_CONTEXT).setScale(SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Wilder's RSI: the first {@code period} changes seed the averages, the rest are smoothed.
   *
   * @return the RSI in the 0-100 range, or null if there aren't enough candles
   */
  private static BigDecimal rsi(List<Candle> candles, int period) {
    if (candles.size() < period + 1) {
      return null;
    }
    final BigDecimal periodValue = BigDecimal.valueOf(period);
    final BigDecimal periodMinusOne = BigDecimal.valueOf(period - 1L);

    BigDecimal gainSum = BigDecimal.ZERO;
    BigDecimal lossSum = BigDecimal.ZERO;
    for (int i = 1; i <= period; i++) {
      final BigDecimal change = candles.get(i).close.subtract(candles.get(i - 1).close);
      if (change.signum() > 0) {
        gainSum = gainSum.add(change);
      } else {
        lossSum = lossSum.add(change.negate());
      }
    }
    BigDecimal avgGain = gainSum.divide(periodValue, SCALE, RoundingMode.HALF_UP);
    BigDecimal avgLoss = lossSum.divide(periodValue, SCALE, RoundingMode.HALF_UP);

    for (int i = period + 1; i < candles.size(); i++) {
      final BigDecimal change = candles.get(i).close.subtract(candles.get(i - 1).close);
      final BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
      final BigDecimal loss = change.signum() < 0 ? change.negate() : BigDecimal.ZERO;
      avgGain =
          avgGain
              .multiply(periodMinusOne)
              .add(gain)
              .divide(periodValue, SCALE, RoundingMode.HALF_UP);
      avgLoss =
          avgLoss
              .multiply(periodMinusOne)
              .add(loss)
              .divide(periodValue, SCALE, RoundingMode.HALF_UP);
    }

    if (avgLoss.signum() == 0) {
      return HUNDRED;
    }
    final BigDecimal relativeStrength = avgGain.divide(avgLoss, SCALE, RoundingMode.HALF_UP);
    return HUNDRED.subtract(
        HUNDRED.divide(BigDecimal.ONE.add(relativeStrength), SCALE, RoundingMode.HALF_UP));
  }

  /** Wilder's Average True Range, or null if there aren't enough candles. */
  private static BigDecimal atr(List<Candle> candles, int period) {
    if (candles.size() < period + 1) {
      return null;
    }
    final BigDecimal periodValue = BigDecimal.valueOf(period);
    final BigDecimal periodMinusOne = BigDecimal.valueOf(period - 1L);

    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 1; i <= period; i++) {
      sum = sum.add(trueRange(candles.get(i), candles.get(i - 1)));
    }
    BigDecimal value = sum.divide(periodValue, SCALE, RoundingMode.HALF_UP);

    for (int i = period + 1; i < candles.size(); i++) {
      value =
          value
              .multiply(periodMinusOne)
              .add(trueRange(candles.get(i), candles.get(i - 1)))
              .divide(periodValue, SCALE, RoundingMode.HALF_UP);
    }
    return value;
  }

  /**
   * Wilder's Average Directional Index - the regime filter.
   *
   * <p>Needs {@code 2 * period + 1} candles: {@code period} of them seed the smoothed TR/DM sums,
   * the next {@code period} produce the DX values that seed the ADX itself.
   *
   * @return the ADX in the 0-100 range, or null if there aren't enough candles
   */
  private static BigDecimal adx(List<Candle> candles, int period) {
    if (candles.size() < 2 * period + 1) {
      return null;
    }
    final BigDecimal periodValue = BigDecimal.valueOf(period);
    final BigDecimal periodMinusOne = BigDecimal.valueOf(period - 1L);

    BigDecimal smoothedTrueRange = BigDecimal.ZERO;
    BigDecimal smoothedPlusDm = BigDecimal.ZERO;
    BigDecimal smoothedMinusDm = BigDecimal.ZERO;
    for (int i = 1; i <= period; i++) {
      smoothedTrueRange = smoothedTrueRange.add(trueRange(candles.get(i), candles.get(i - 1)));
      smoothedPlusDm =
          smoothedPlusDm.add(plusDirectionalMovement(candles.get(i), candles.get(i - 1)));
      smoothedMinusDm =
          smoothedMinusDm.add(minusDirectionalMovement(candles.get(i), candles.get(i - 1)));
    }

    final List<BigDecimal> directionalIndexes = new ArrayList<>();
    for (int i = period + 1; i < candles.size(); i++) {
      final Candle current = candles.get(i);
      final Candle previous = candles.get(i - 1);
      smoothedTrueRange =
          wilderSmoothSum(smoothedTrueRange, trueRange(current, previous), periodValue);
      smoothedPlusDm =
          wilderSmoothSum(
              smoothedPlusDm, plusDirectionalMovement(current, previous), periodValue);
      smoothedMinusDm =
          wilderSmoothSum(
              smoothedMinusDm, minusDirectionalMovement(current, previous), periodValue);
      directionalIndexes.add(
          directionalIndex(smoothedPlusDm, smoothedMinusDm, smoothedTrueRange));
    }

    if (directionalIndexes.size() < period) {
      return null;
    }
    BigDecimal value = BigDecimal.ZERO;
    for (int i = 0; i < period; i++) {
      value = value.add(directionalIndexes.get(i));
    }
    value = value.divide(periodValue, SCALE, RoundingMode.HALF_UP);
    for (int i = period; i < directionalIndexes.size(); i++) {
      value =
          value
              .multiply(periodMinusOne)
              .add(directionalIndexes.get(i))
              .divide(periodValue, SCALE, RoundingMode.HALF_UP);
    }
    return value;
  }

  /** Wilder's running sum: {@code previous - previous / period + latest}. */
  private static BigDecimal wilderSmoothSum(
      BigDecimal previous, BigDecimal latest, BigDecimal period) {
    return previous
        .subtract(previous.divide(period, SCALE, RoundingMode.HALF_UP))
        .add(latest);
  }

  private static BigDecimal directionalIndex(
      BigDecimal smoothedPlusDm, BigDecimal smoothedMinusDm, BigDecimal smoothedTrueRange) {
    if (smoothedTrueRange.signum() == 0) {
      return BigDecimal.ZERO;
    }
    final BigDecimal plusDi =
        HUNDRED.multiply(smoothedPlusDm).divide(smoothedTrueRange, SCALE, RoundingMode.HALF_UP);
    final BigDecimal minusDi =
        HUNDRED.multiply(smoothedMinusDm).divide(smoothedTrueRange, SCALE, RoundingMode.HALF_UP);
    final BigDecimal sum = plusDi.add(minusDi);
    if (sum.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return HUNDRED
        .multiply(plusDi.subtract(minusDi).abs())
        .divide(sum, SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal trueRange(Candle current, Candle previous) {
    final BigDecimal highLow = current.high.subtract(current.low);
    final BigDecimal highClose = current.high.subtract(previous.close).abs();
    final BigDecimal lowClose = current.low.subtract(previous.close).abs();
    return highLow.max(highClose).max(lowClose);
  }

  private static BigDecimal plusDirectionalMovement(Candle current, Candle previous) {
    final BigDecimal upMove = current.high.subtract(previous.high);
    final BigDecimal downMove = previous.low.subtract(current.low);
    return upMove.signum() > 0 && upMove.compareTo(downMove) > 0 ? upMove : BigDecimal.ZERO;
  }

  private static BigDecimal minusDirectionalMovement(Candle current, Candle previous) {
    final BigDecimal upMove = current.high.subtract(previous.high);
    final BigDecimal downMove = previous.low.subtract(current.low);
    return downMove.signum() > 0 && downMove.compareTo(upMove) > 0 ? downMove : BigDecimal.ZERO;
  }

  // ---------------------------------------------------------------------------
  // Config loading
  // ---------------------------------------------------------------------------

  private void loadConfig(StrategyConfig config) {
    candleSeconds = requirePositiveInt(config, "candle-seconds");
    bollingerPeriod = requirePositiveInt(config, "bollinger-period");
    bollingerStdDevMultiplier = requirePositiveDecimal(config, "bollinger-std-dev-multiplier");
    rsiPeriod = requirePositiveInt(config, "rsi-period");
    rsiOversoldThreshold = requireDecimal(config, "rsi-oversold-threshold");
    rsiOverboughtThreshold = requireDecimal(config, "rsi-overbought-threshold");
    rsiExitThreshold = requireDecimal(config, "rsi-exit-threshold");
    atrPeriod = requirePositiveInt(config, "atr-period");
    atrStopLossMultiplier = requirePositiveDecimal(config, "atr-stop-loss-multiplier");
    adxPeriod = requirePositiveInt(config, "adx-period");
    adxTrendThreshold = requirePositiveDecimal(config, "adx-trend-threshold");
    maxHoldingCandles = requirePositiveInt(config, "max-holding-candles");
    startingCapital = requirePositiveDecimal(config, "starting-capital");
    riskPerTradePercentage = requirePositiveDecimal(config, "risk-per-trade-percentage");
    counterCurrencyBuyOrderAmount =
        requirePositiveDecimal(config, "counter-currency-buy-order-amount");
    maxDrawdownPercentage = requirePositiveDecimal(config, "max-drawdown-percentage");
    testMode = Boolean.parseBoolean(requireString(config, "test-mode"));

    if (rsiOversoldThreshold.compareTo(rsiExitThreshold) >= 0
        || rsiExitThreshold.compareTo(rsiOverboughtThreshold) >= 0) {
      throw new IllegalArgumentException(
          "RSI thresholds must satisfy oversold ("
              + rsiOversoldThreshold
              + ") < exit ("
              + rsiExitThreshold
              + ") < overbought ("
              + rsiOverboughtThreshold
              + ")");
    }
    if (riskPerTradePercentage.compareTo(HUNDRED) > 0) {
      throw new IllegalArgumentException(
          "risk-per-trade-percentage must be <= 100, got: " + riskPerTradePercentage);
    }
    if (maxDrawdownPercentage.compareTo(HUNDRED) > 0) {
      throw new IllegalArgumentException(
          "max-drawdown-percentage must be <= 100, got: " + maxDrawdownPercentage);
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

  private static BigDecimal requirePositiveDecimal(StrategyConfig config, String key) {
    final BigDecimal value = requireDecimal(config, key);
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(
          "Config item '" + key + "' must be positive, got: " + value);
    }
    return value;
  }

  private static int requirePositiveInt(StrategyConfig config, String key) {
    final String raw = requireString(config, key);
    final int value;
    try {
      value = Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Config item '" + key + "' is not a valid integer: " + raw, e);
    }
    if (value <= 0) {
      throw new IllegalArgumentException(
          "Config item '" + key + "' must be positive, got: " + value);
    }
    return value;
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
        "{} Indicators - price={}, close={}, sma={}, upper={}, lower={}, rsi={}, atr={}, adx={}",
        market.getName(),
        fmt(currentPrice),
        fmt(lastClose()),
        fmt(middleBand),
        fmt(upperBand),
        fmt(lowerBand),
        fmt(rsiValue),
        fmt(atrValue),
        fmt(adxValue));
  }
}
