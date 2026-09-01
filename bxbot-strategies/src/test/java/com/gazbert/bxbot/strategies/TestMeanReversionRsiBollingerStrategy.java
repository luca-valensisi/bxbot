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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.OpenOrder;
import com.gazbert.bxbot.trading.api.OrderType;
import com.gazbert.bxbot.trading.api.Ticker;
import com.gazbert.bxbot.trading.api.TradingApi;
import com.gazbert.bxbot.trading.api.TradingApiException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.powermock.reflect.Whitebox;

/**
 * Tests the Mean Reversion RSI / Bollinger Bands Strategy.
 *
 * <p>Periods are deliberately tiny so the warm-up is 5 closed candles:
 * {@code max(bollinger 5, rsi 3 + 1, atr 3 + 1, adx 2 * 2 + 1) = 5}.
 *
 * <p>Two techniques are used to drive the strategy:
 *
 * <ul>
 *   <li><em>Entry</em> paths inject a candle history plus the in-progress candle, then send one
 *       tick in a <strong>new</strong> bucket. That closes the injected candle and makes the
 *       strategy compute the real indicators over a hand-checked series.
 *   <li><em>Exit</em> paths inject the indicator values directly and send a tick in the
 *       <strong>same</strong> bucket, so nothing is recomputed and the injected values stand.
 * </ul>
 *
 * @author luca valensisi
 */
public class TestMeanReversionRsiBollingerStrategy {

  private static final String MARKET_ID = "btc_usd";
  private static final String MARKET_NAME = "BTC/USD";
  private static final String BASE_CURRENCY = "BTC";
  private static final String COUNTER_CURRENCY = "USD";

  private static final String CANDLE_SECONDS = "60";
  private static final String BOLLINGER_PERIOD = "5";
  private static final String BOLLINGER_MULTIPLIER = "1.0";
  private static final String RSI_PERIOD = "3";
  private static final String RSI_OVERSOLD = "30";
  private static final String RSI_OVERBOUGHT = "70";
  private static final String RSI_EXIT = "50";
  private static final String ATR_PERIOD = "3";
  private static final String ATR_MULTIPLIER = "0.5";
  private static final String ADX_PERIOD = "2";
  private static final String ADX_THRESHOLD = "25";
  private static final String MAX_HOLDING_CANDLES = "3";
  private static final String STARTING_CAPITAL = "1000";
  private static final String RISK_PER_TRADE = "1.0";
  private static final String BUY_ORDER_AMOUNT = "500";
  private static final String MAX_DRAWDOWN = "15.0";

  /** Bucket 10 with candle-seconds = 60. */
  private static final long SAME_BUCKET_TIMESTAMP = 600L;

  /** Bucket 11 with candle-seconds = 60 - rolls the candle over. */
  private static final long NEXT_BUCKET_TIMESTAMP = 660L;

  private TradingApi tradingApi;
  private Market market;
  private StrategyConfig config;

  /** Initialises mocks shared by every test. */
  @Before
  public void setUp() {
    tradingApi = createMock(TradingApi.class);
    market = createMock(Market.class);
    config = createMock(StrategyConfig.class);
  }

  // ---------------------------------------------------------------------------
  // Config validation
  // ---------------------------------------------------------------------------

  @Test
  public void testMissingConfigItemThrows() {
    expect(config.getConfigItem("candle-seconds")).andReturn(null);
    replay(config, tradingApi, market);

    final MeanReversionRsiBollingerStrategy strategy = new MeanReversionRsiBollingerStrategy();
    assertThrows(IllegalArgumentException.class, () -> strategy.init(tradingApi, market, config));
    verify(config, tradingApi, market);
  }

  @Test
  public void testNonNumericConfigItemThrows() {
    expect(config.getConfigItem("candle-seconds")).andReturn("not-a-number");
    replay(config, tradingApi, market);

    final MeanReversionRsiBollingerStrategy strategy = new MeanReversionRsiBollingerStrategy();
    assertThrows(IllegalArgumentException.class, () -> strategy.init(tradingApi, market, config));
    verify(config, tradingApi, market);
  }

  @Test
  public void testNonPositivePeriodThrows() {
    expect(config.getConfigItem("candle-seconds")).andReturn("0");
    replay(config, tradingApi, market);

    final MeanReversionRsiBollingerStrategy strategy = new MeanReversionRsiBollingerStrategy();
    assertThrows(IllegalArgumentException.class, () -> strategy.init(tradingApi, market, config));
    verify(config, tradingApi, market);
  }

  @Test
  public void testNonPositiveDecimalThrows() {
    expect(config.getConfigItem("candle-seconds")).andReturn(CANDLE_SECONDS);
    expect(config.getConfigItem("bollinger-period")).andReturn(BOLLINGER_PERIOD);
    expect(config.getConfigItem("bollinger-std-dev-multiplier")).andReturn("0");
    replay(config, tradingApi, market);

    final MeanReversionRsiBollingerStrategy strategy = new MeanReversionRsiBollingerStrategy();
    assertThrows(IllegalArgumentException.class, () -> strategy.init(tradingApi, market, config));
    verify(config, tradingApi, market);
  }

  @Test
  public void testInconsistentRsiThresholdsThrow() {
    // oversold 60 >= exit 50 - illegal
    expectConfig("60", RSI_OVERBOUGHT, RSI_EXIT, RISK_PER_TRADE, MAX_DRAWDOWN, "true");
    replay(config, tradingApi, market);

    final MeanReversionRsiBollingerStrategy strategy = new MeanReversionRsiBollingerStrategy();
    assertThrows(IllegalArgumentException.class, () -> strategy.init(tradingApi, market, config));
    verify(config, tradingApi, market);
  }

  @Test
  public void testRiskPerTradeAboveOneHundredThrows() {
    expectConfig(RSI_OVERSOLD, RSI_OVERBOUGHT, RSI_EXIT, "101", MAX_DRAWDOWN, "true");
    replay(config, tradingApi, market);

    final MeanReversionRsiBollingerStrategy strategy = new MeanReversionRsiBollingerStrategy();
    assertThrows(IllegalArgumentException.class, () -> strategy.init(tradingApi, market, config));
    verify(config, tradingApi, market);
  }

  @Test
  public void testMaxDrawdownAboveOneHundredThrows() {
    expectConfig(RSI_OVERSOLD, RSI_OVERBOUGHT, RSI_EXIT, RISK_PER_TRADE, "101", "true");
    replay(config, tradingApi, market);

    final MeanReversionRsiBollingerStrategy strategy = new MeanReversionRsiBollingerStrategy();
    assertThrows(IllegalArgumentException.class, () -> strategy.init(tradingApi, market, config));
    verify(config, tradingApi, market);
  }

  // ---------------------------------------------------------------------------
  // Candle aggregation and warm-up
  // ---------------------------------------------------------------------------

  @Test
  public void testWarmUpPhaseDoesNotPlaceOrders() throws Exception {
    expectDefaultConfig("true");
    expectMarketStubs();

    final Ticker ticker = createMock(Ticker.class);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker).times(4);
    expect(ticker.getLast()).andReturn(bd("100")).times(4);
    // Null timestamps fall back to the wall clock - all four ticks land in the same bucket.
    expect(ticker.getTimestamp()).andReturn(null).times(4);

    replay(config, tradingApi, market, ticker);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    for (int i = 0; i < 4; i++) {
      strategy.execute();
    }
    assertEquals(0L, (long) Whitebox.getInternalState(strategy, "closedCandleCount"));
    verify(config, tradingApi, market, ticker);
  }

  @Test
  public void testTicksInSameBucketExtendTheCandleWithoutClosingIt() throws Exception {
    expectDefaultConfig("true");
    expectMarketStubs();

    final Ticker ticker = createMock(Ticker.class);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker).times(3);
    expect(ticker.getLast()).andReturn(bd("100"));
    expect(ticker.getLast()).andReturn(bd("120"));
    expect(ticker.getLast()).andReturn(bd("90"));
    expect(ticker.getTimestamp()).andReturn(SAME_BUCKET_TIMESTAMP).times(3);

    replay(config, tradingApi, market, ticker);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    for (int i = 0; i < 3; i++) {
      strategy.execute();
    }

    assertEquals(0L, (long) Whitebox.getInternalState(strategy, "closedCandleCount"));
    final Object candle = Whitebox.getInternalState(strategy, "currentCandle");
    assertEquals(bd("100"), candleField(candle, "open"));
    assertEquals(bd("120"), candleField(candle, "high"));
    assertEquals(bd("90"), candleField(candle, "low"));
    assertEquals(bd("90"), candleField(candle, "close"));
    verify(config, tradingApi, market, ticker);
  }

  @Test
  public void testMillisecondTimestampIsNormalisedToSeconds() throws Exception {
    expectDefaultConfig("true");
    expectMarketStubs();

    final Ticker ticker = createMock(Ticker.class);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker).times(2);
    expect(ticker.getLast()).andReturn(bd("100")).times(2);
    // 1_700_000_000_000 ms -> bucket 28333333; +60_000 ms -> bucket 28333334.
    expect(ticker.getTimestamp()).andReturn(1_700_000_000_000L);
    expect(ticker.getTimestamp()).andReturn(1_700_000_060_000L);

    replay(config, tradingApi, market, ticker);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    strategy.execute();
    strategy.execute();

    assertEquals(1L, (long) Whitebox.getInternalState(strategy, "closedCandleCount"));
    verify(config, tradingApi, market, ticker);
  }

  @Test
  public void testNullTickerSkipsCycle() throws Exception {
    expectDefaultConfig("true");
    expectMarketStubs();
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(null);
    replay(config, tradingApi, market);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    strategy.execute();
    verify(config, tradingApi, market);
  }

  @Test
  public void testNullLastPriceSkipsCycle() throws Exception {
    expectDefaultConfig("true");
    expectMarketStubs();

    final Ticker ticker = createMock(Ticker.class);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker);
    expect(ticker.getLast()).andReturn(null);
    replay(config, tradingApi, market, ticker);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    strategy.execute();
    verify(config, tradingApi, market, ticker);
  }

  // ---------------------------------------------------------------------------
  // Entry signal - real indicators over a hand-checked series
  // ---------------------------------------------------------------------------

  /**
   * Ranging series: closes 100, 90, 100, 90, 70 with a flat 110/70 high/low envelope.
   *
   * <p>SMA(5) = 90, sd = 10.95445115, so with a 1.0 multiplier lowerBand = 79.04554885 and the
   * close of 70 is below it. RSI(3) = 16.67 &lt; 30. Every high and low is identical, so +DM and
   * -DM are both zero and ADX = 0 &lt; 25 - a ranging market. ATR(3) = 40.
   */
  private static List<Object> rangingSeries() throws Exception {
    final List<Object> candles = new ArrayList<>();
    candles.add(candle("100", "110", "70", "100"));
    candles.add(candle("90", "110", "70", "90"));
    candles.add(candle("100", "110", "70", "100"));
    candles.add(candle("90", "110", "70", "90"));
    return candles;
  }

  @Test
  public void testTestModeEntrySignalDoesNotCallCreateOrder() throws Exception {
    expectDefaultConfig("true");
    expectMarketStubs();
    expectEntryTickerAndFees();

    replay(config, tradingApi, market, tickerForEntry);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeForCandleClose(strategy, rangingSeries(), candle("70", "110", "70", "70"));

    strategy.execute();

    // createOrder was never recorded on the mock, so a real order would have failed the test.
    assertEquals("LONG", positionState(strategy));
    assertEquals(bd("70"), Whitebox.getInternalState(strategy, "entryPrice"));
    assertEquals(bd("50.00000000"), Whitebox.getInternalState(strategy, "stopLossPrice"));
    assertEquals(bd("0.50000000"), Whitebox.getInternalState(strategy, "activeOrderAmount"));
    verify(config, tradingApi, market, tickerForEntry);
  }

  @Test
  public void testLiveModeEntrySignalPlacesBuyOrder() throws Exception {
    expectDefaultConfig("false");
    expectMarketStubs();
    expectEntryTickerAndFees();
    expect(tradingApi.createOrder(MARKET_ID, OrderType.BUY, bd("0.50000000"), bd("70")))
        .andReturn("ORDER-123");

    replay(config, tradingApi, market, tickerForEntry);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeForCandleClose(strategy, rangingSeries(), candle("70", "110", "70", "70"));

    strategy.execute();

    assertEquals("WAITING_BUY_FILL", positionState(strategy));
    verify(config, tradingApi, market, tickerForEntry);
  }

  /**
   * Trending series: closes 100, 90, 80, 70, 55 with highs/lows tracking them down.
   *
   * <p>Bollinger and RSI both fire, but every bar makes a lower high and a lower low so ADX = 100
   * and the regime filter must veto the entry. Live mode is used so an order would fail the test.
   */
  @Test
  public void testAdxRegimeFilterBlocksEntryInTrendingMarket() throws Exception {
    expectDefaultConfig("false");
    expectMarketStubs();

    final Ticker ticker = createMock(Ticker.class);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker);
    expect(ticker.getLast()).andReturn(bd("55"));
    expect(ticker.getTimestamp()).andReturn(NEXT_BUCKET_TIMESTAMP);
    replay(config, tradingApi, market, ticker);

    final List<Object> candles = new ArrayList<>();
    candles.add(candle("100", "105", "95", "100"));
    candles.add(candle("90", "95", "85", "90"));
    candles.add(candle("80", "85", "75", "80"));
    candles.add(candle("70", "75", "65", "70"));

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeForCandleClose(strategy, candles, candle("55", "60", "50", "55"));

    strategy.execute();

    assertEquals("FLAT", positionState(strategy));
    assertEquals(bd("100.00000000"), Whitebox.getInternalState(strategy, "adxValue"));
    verify(config, tradingApi, market, ticker);
  }

  /**
   * Rising series: closes 70, 80, 90, 100, 115 - close above the upper band with RSI = 100.
   *
   * <p>Spot-only, so nothing is traded; the run must simply stay FLAT after logging the setup.
   */
  @Test
  public void testOverboughtSetupIsLoggedButNotTraded() throws Exception {
    expectDefaultConfig("false");
    expectMarketStubs();

    final Ticker ticker = createMock(Ticker.class);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker);
    expect(ticker.getLast()).andReturn(bd("115"));
    expect(ticker.getTimestamp()).andReturn(NEXT_BUCKET_TIMESTAMP);
    replay(config, tradingApi, market, ticker);

    final List<Object> candles = new ArrayList<>();
    candles.add(candle("70", "75", "65", "70"));
    candles.add(candle("80", "85", "75", "80"));
    candles.add(candle("90", "95", "85", "90"));
    candles.add(candle("100", "105", "95", "100"));

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeForCandleClose(strategy, candles, candle("115", "120", "110", "115"));

    strategy.execute();

    assertEquals("FLAT", positionState(strategy));
    assertEquals(bd("100"), Whitebox.getInternalState(strategy, "rsiValue"));
    verify(config, tradingApi, market, ticker);
  }

  @Test
  public void testNoEntryWhenIndicatorsAreNeutral() throws Exception {
    expectDefaultConfig("false");
    expectMarketStubs();

    final Ticker ticker = createMock(Ticker.class);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker);
    expect(ticker.getLast()).andReturn(bd("100"));
    expect(ticker.getTimestamp()).andReturn(NEXT_BUCKET_TIMESTAMP);
    replay(config, tradingApi, market, ticker);

    final List<Object> candles = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      candles.add(candle("100", "110", "90", "100"));
    }

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeForCandleClose(strategy, candles, candle("100", "110", "90", "100"));

    strategy.execute();

    assertEquals("FLAT", positionState(strategy));
    verify(config, tradingApi, market, ticker);
  }

  // ---------------------------------------------------------------------------
  // Exit paths - injected indicators, tick in the same bucket
  // ---------------------------------------------------------------------------

  @Test
  public void testStopLossClosesPositionInTestMode() throws Exception {
    final MeanReversionRsiBollingerStrategy strategy = holdingStrategy("true", bd("70"));

    strategy.execute();

    assertEquals("FLAT", positionState(strategy));
    // (70 - 100) * 1 = -30
    assertEquals(bd("-30.00000000"), Whitebox.getInternalState(strategy, "realizedPnl"));
    verifyAll();
  }

  @Test
  public void testTargetReachedClosesPosition() throws Exception {
    final MeanReversionRsiBollingerStrategy strategy = holdingStrategy("true", bd("130"));

    strategy.execute();

    assertEquals("FLAT", positionState(strategy));
    assertEquals(bd("30.00000000"), Whitebox.getInternalState(strategy, "realizedPnl"));
    verifyAll();
  }

  @Test
  public void testRsiExitClosesPosition() throws Exception {
    final MeanReversionRsiBollingerStrategy strategy = holdingStrategy("true", bd("100"));
    Whitebox.setInternalState(strategy, "rsiValue", bd("60"));

    strategy.execute();

    assertEquals("FLAT", positionState(strategy));
    verifyAll();
  }

  @Test
  public void testTimeExitClosesPosition() throws Exception {
    final MeanReversionRsiBollingerStrategy strategy = holdingStrategy("true", bd("100"));
    // maxHoldingCandles is 3 and closedCandleCount is 10.
    Whitebox.setInternalState(strategy, "entryCandleCount", 7L);

    strategy.execute();

    assertEquals("FLAT", positionState(strategy));
    verifyAll();
  }

  @Test
  public void testPositionIsHeldWhenNoExitConditionIsMet() throws Exception {
    final MeanReversionRsiBollingerStrategy strategy = holdingStrategy("true", bd("100"));

    strategy.execute();

    assertEquals("LONG", positionState(strategy));
    assertEquals(BigDecimal.ZERO, Whitebox.getInternalState(strategy, "realizedPnl"));
    verifyAll();
  }

  @Test
  public void testLiveModeExitPlacesSellOrder() throws Exception {
    expectDefaultConfig("false");
    expectMarketStubs();
    expectHoldingTicker(bd("70"));
    expect(tradingApi.createOrder(MARKET_ID, OrderType.SELL, bd("1"), bd("70")))
        .andReturn("SELL-1");
    replay(config, tradingApi, market, tickerForHolding);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeHoldingState(strategy);

    strategy.execute();

    assertEquals("WAITING_SELL_FILL", positionState(strategy));
    verify(config, tradingApi, market, tickerForHolding);
  }

  // ---------------------------------------------------------------------------
  // Pending fill paths
  // ---------------------------------------------------------------------------

  @Test
  public void testWaitingBuyFillTransitionsToLongWhenOrderFilled() throws Exception {
    expectDefaultConfig("false");
    expectMarketStubs();
    expectHoldingTicker(bd("100"));
    expect(tradingApi.getYourOpenOrders(MARKET_ID)).andReturn(Collections.emptyList());
    replay(config, tradingApi, market, tickerForHolding);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeHoldingState(strategy);
    setPositionState(strategy, "WAITING_BUY_FILL");
    Whitebox.setInternalState(strategy, "activeOrderId", "ORDER-123");
    Whitebox.setInternalState(strategy, "entryStopDistance", bd("20"));

    strategy.execute();

    assertEquals("LONG", positionState(strategy));
    assertEquals(bd("100"), Whitebox.getInternalState(strategy, "entryPrice"));
    assertEquals(bd("80"), Whitebox.getInternalState(strategy, "stopLossPrice"));
    verify(config, tradingApi, market, tickerForHolding);
  }

  @Test
  public void testWaitingBuyFillStaysPendingWhenOrderStillOpen() throws Exception {
    expectDefaultConfig("false");
    expectMarketStubs();
    expectHoldingTicker(bd("100"));

    final OpenOrder openOrder = createMock(OpenOrder.class);
    expect(openOrder.getId()).andReturn("ORDER-123");
    final List<OpenOrder> openOrders = new ArrayList<>();
    openOrders.add(openOrder);
    expect(tradingApi.getYourOpenOrders(MARKET_ID)).andReturn(openOrders);
    replay(config, tradingApi, market, tickerForHolding, openOrder);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeHoldingState(strategy);
    setPositionState(strategy, "WAITING_BUY_FILL");
    Whitebox.setInternalState(strategy, "activeOrderId", "ORDER-123");

    strategy.execute();

    assertEquals("WAITING_BUY_FILL", positionState(strategy));
    verify(config, tradingApi, market, tickerForHolding, openOrder);
  }

  @Test
  public void testWaitingSellFillSettlesTradeWhenOrderFilled() throws Exception {
    expectDefaultConfig("false");
    expectMarketStubs();
    expectHoldingTicker(bd("130"));
    expect(tradingApi.getYourOpenOrders(MARKET_ID)).andReturn(Collections.emptyList());
    replay(config, tradingApi, market, tickerForHolding);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeHoldingState(strategy);
    setPositionState(strategy, "WAITING_SELL_FILL");
    Whitebox.setInternalState(strategy, "activeOrderId", "SELL-1");

    strategy.execute();

    assertEquals("FLAT", positionState(strategy));
    assertEquals(bd("30.00000000"), Whitebox.getInternalState(strategy, "realizedPnl"));
    verify(config, tradingApi, market, tickerForHolding);
  }

  @Test
  public void testWaitingSellFillStaysPendingWhenOrderStillOpen() throws Exception {
    expectDefaultConfig("false");
    expectMarketStubs();
    expectHoldingTicker(bd("130"));

    final OpenOrder openOrder = createMock(OpenOrder.class);
    expect(openOrder.getId()).andReturn("SELL-1");
    final List<OpenOrder> openOrders = new ArrayList<>();
    openOrders.add(openOrder);
    expect(tradingApi.getYourOpenOrders(MARKET_ID)).andReturn(openOrders);
    replay(config, tradingApi, market, tickerForHolding, openOrder);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeHoldingState(strategy);
    setPositionState(strategy, "WAITING_SELL_FILL");
    Whitebox.setInternalState(strategy, "activeOrderId", "SELL-1");

    strategy.execute();

    assertEquals("WAITING_SELL_FILL", positionState(strategy));
    verify(config, tradingApi, market, tickerForHolding, openOrder);
  }

  // ---------------------------------------------------------------------------
  // Drawdown kill switch
  // ---------------------------------------------------------------------------

  @Test
  public void testDrawdownKillSwitchHaltsStrategyInTestMode() throws Exception {
    expectDefaultConfig("true");
    expectMarketStubs();

    final Ticker ticker = createMock(Ticker.class);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker).times(2);
    expect(ticker.getLast()).andReturn(bd("700")).times(2);
    expect(ticker.getTimestamp()).andReturn(SAME_BUCKET_TIMESTAMP).times(2);
    replay(config, tradingApi, market, ticker);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeHoldingState(strategy);
    // Entry at 1000, stop at 800, one unit: a fill at 700 is a 300 loss on 1000 of capital.
    Whitebox.setInternalState(strategy, "entryPrice", bd("1000"));
    Whitebox.setInternalState(strategy, "stopLossPrice", bd("800"));

    strategy.execute();

    assertEquals("HALTED", positionState(strategy));
    assertEquals(bd("-300.00000000"), Whitebox.getInternalState(strategy, "realizedPnl"));

    // A halted strategy must do nothing on the next cycle.
    strategy.execute();
    assertEquals("HALTED", positionState(strategy));
    verify(config, tradingApi, market, ticker);
  }

  @Test
  public void testDrawdownKillSwitchThrowsStrategyExceptionInLiveMode() throws Exception {
    expectDefaultConfig("false");
    expectMarketStubs();
    expectHoldingTicker(bd("700"));
    expect(tradingApi.getYourOpenOrders(MARKET_ID)).andReturn(Collections.emptyList());
    replay(config, tradingApi, market, tickerForHolding);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeHoldingState(strategy);
    setPositionState(strategy, "WAITING_SELL_FILL");
    Whitebox.setInternalState(strategy, "activeOrderId", "SELL-1");
    Whitebox.setInternalState(strategy, "entryPrice", bd("1000"));

    assertThrows(StrategyException.class, strategy::execute);
    assertEquals("HALTED", positionState(strategy));
    verify(config, tradingApi, market, tickerForHolding);
  }

  // ---------------------------------------------------------------------------
  // Exception handling
  // ---------------------------------------------------------------------------

  @Test
  public void testNetworkExceptionIsSwallowed() throws Exception {
    expectDefaultConfig("true");
    expectMarketStubs();
    expect(tradingApi.getTicker(MARKET_ID))
        .andThrow(new ExchangeNetworkException("Simulated network error"));
    replay(config, tradingApi, market);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    strategy.execute();
    verify(config, tradingApi, market);
  }

  @Test
  public void testTradingApiExceptionWrapsToStrategyException() throws Exception {
    expectDefaultConfig("true");
    expectMarketStubs();
    expect(tradingApi.getTicker(MARKET_ID))
        .andThrow(new TradingApiException("Simulated API error"));
    replay(config, tradingApi, market);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    assertThrows(StrategyException.class, strategy::execute);
    verify(config, tradingApi, market);
  }

  @Test
  public void testFeeLookupFailureDegradesToZeroFees() throws Exception {
    expectDefaultConfig("true");
    expectMarketStubs();

    tickerForEntry = createMock(Ticker.class);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(tickerForEntry);
    expect(tickerForEntry.getLast()).andReturn(bd("70"));
    expect(tickerForEntry.getTimestamp()).andReturn(NEXT_BUCKET_TIMESTAMP);
    expect(tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(MARKET_ID))
        .andThrow(new TradingApiException("no fees for you"));
    replay(config, tradingApi, market, tickerForEntry);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeForCandleClose(strategy, rangingSeries(), candle("70", "110", "70", "70"));

    // The failure is swallowed - the strategy still opens the simulated position.
    strategy.execute();

    assertEquals("LONG", positionState(strategy));
    assertEquals(BigDecimal.ZERO, Whitebox.getInternalState(strategy, "buyFeeFraction"));
    assertEquals(BigDecimal.ZERO, Whitebox.getInternalState(strategy, "sellFeeFraction"));
    verify(config, tradingApi, market, tickerForEntry);
  }

  @Test
  public void testExchangeFeesAreDeductedFromRealisedPnl() throws Exception {
    final MeanReversionRsiBollingerStrategy strategy = holdingStrategy("true", bd("130"));
    Whitebox.setInternalState(strategy, "buyFeeFraction", bd("0.01"));
    Whitebox.setInternalState(strategy, "sellFeeFraction", bd("0.01"));

    strategy.execute();

    // gross 30, fees 100 * 0.01 + 130 * 0.01 = 2.30 -> net 27.70
    assertEquals(bd("27.70000000"), Whitebox.getInternalState(strategy, "realizedPnl"));
    verifyAll();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private Ticker tickerForEntry;
  private Ticker tickerForHolding;

  private MeanReversionRsiBollingerStrategy buildAndInitStrategy() {
    final MeanReversionRsiBollingerStrategy strategy = new MeanReversionRsiBollingerStrategy();
    strategy.init(tradingApi, market, config);
    return strategy;
  }

  private void expectMarketStubs() {
    expect(market.getId()).andStubReturn(MARKET_ID);
    expect(market.getBaseCurrency()).andStubReturn(BASE_CURRENCY);
    expect(market.getCounterCurrency()).andStubReturn(COUNTER_CURRENCY);
  }

  private void expectDefaultConfig(String testMode) {
    expectConfig(RSI_OVERSOLD, RSI_OVERBOUGHT, RSI_EXIT, RISK_PER_TRADE, MAX_DRAWDOWN, testMode);
  }

  /** Records the config reads in the exact order {@code loadConfig} performs them. */
  private void expectConfig(
      String rsiOversold,
      String rsiOverbought,
      String rsiExit,
      String riskPerTrade,
      String maxDrawdown,
      String testMode) {
    expect(config.getConfigItem("candle-seconds")).andReturn(CANDLE_SECONDS);
    expect(config.getConfigItem("bollinger-period")).andReturn(BOLLINGER_PERIOD);
    expect(config.getConfigItem("bollinger-std-dev-multiplier")).andReturn(BOLLINGER_MULTIPLIER);
    expect(config.getConfigItem("rsi-period")).andReturn(RSI_PERIOD);
    expect(config.getConfigItem("rsi-oversold-threshold")).andReturn(rsiOversold);
    expect(config.getConfigItem("rsi-overbought-threshold")).andReturn(rsiOverbought);
    expect(config.getConfigItem("rsi-exit-threshold")).andReturn(rsiExit);
    expect(config.getConfigItem("atr-period")).andReturn(ATR_PERIOD);
    expect(config.getConfigItem("atr-stop-loss-multiplier")).andReturn(ATR_MULTIPLIER);
    expect(config.getConfigItem("adx-period")).andReturn(ADX_PERIOD);
    expect(config.getConfigItem("adx-trend-threshold")).andReturn(ADX_THRESHOLD);
    expect(config.getConfigItem("max-holding-candles")).andReturn(MAX_HOLDING_CANDLES);
    expect(config.getConfigItem("starting-capital")).andReturn(STARTING_CAPITAL);
    expect(config.getConfigItem("risk-per-trade-percentage")).andReturn(riskPerTrade);
    expect(config.getConfigItem("counter-currency-buy-order-amount")).andReturn(BUY_ORDER_AMOUNT);
    expect(config.getConfigItem("max-drawdown-percentage")).andReturn(maxDrawdown);
    expect(config.getConfigItem("test-mode")).andReturn(testMode);
    expect(market.getName()).andStubReturn(MARKET_NAME);
  }

  private void expectEntryTickerAndFees() throws Exception {
    tickerForEntry = createMock(Ticker.class);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(tickerForEntry);
    expect(tickerForEntry.getLast()).andReturn(bd("70"));
    expect(tickerForEntry.getTimestamp()).andReturn(NEXT_BUCKET_TIMESTAMP);
    expect(tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(MARKET_ID))
        .andReturn(BigDecimal.ZERO);
    expect(tradingApi.getPercentageOfSellOrderTakenForExchangeFee(MARKET_ID))
        .andReturn(BigDecimal.ZERO);
  }

  private void expectHoldingTicker(BigDecimal price) throws Exception {
    tickerForHolding = createMock(Ticker.class);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(tickerForHolding);
    expect(tickerForHolding.getLast()).andReturn(price);
    expect(tickerForHolding.getTimestamp()).andReturn(SAME_BUCKET_TIMESTAMP);
  }

  private void verifyAll() {
    verify(config, tradingApi, market, tickerForHolding);
  }

  /** Builds a strategy already holding a long position, ready for one same-bucket tick. */
  private MeanReversionRsiBollingerStrategy holdingStrategy(String testMode, BigDecimal price)
      throws Exception {
    expectDefaultConfig(testMode);
    expectMarketStubs();
    expectHoldingTicker(price);
    replay(config, tradingApi, market, tickerForHolding);

    final MeanReversionRsiBollingerStrategy strategy = buildAndInitStrategy();
    primeHoldingState(strategy);
    return strategy;
  }

  /**
   * Injects a completed warm-up, a LONG position and an in-progress candle in bucket 10, so a tick
   * with {@link #SAME_BUCKET_TIMESTAMP} leaves the indicators untouched.
   */
  private void primeHoldingState(MeanReversionRsiBollingerStrategy strategy) throws Exception {
    Whitebox.setInternalState(strategy, "closedCandleCount", 10L);
    Whitebox.setInternalState(strategy, "currentBucket", 10L);
    Whitebox.setInternalState(strategy, "currentCandle", candle("100", "110", "90", "100"));
    injectCandles(strategy, List.of(candle("100", "110", "90", "100")));

    Whitebox.setInternalState(strategy, "middleBand", bd("120"));
    Whitebox.setInternalState(strategy, "upperBand", bd("140"));
    Whitebox.setInternalState(strategy, "lowerBand", bd("100"));
    Whitebox.setInternalState(strategy, "rsiValue", bd("40"));
    Whitebox.setInternalState(strategy, "atrValue", bd("40"));
    Whitebox.setInternalState(strategy, "adxValue", bd("10"));

    setPositionState(strategy, "LONG");
    Whitebox.setInternalState(strategy, "entryPrice", bd("100"));
    Whitebox.setInternalState(strategy, "stopLossPrice", bd("80"));
    Whitebox.setInternalState(strategy, "activeOrderAmount", bd("1"));
    Whitebox.setInternalState(strategy, "entryCandleCount", 10L);
    Whitebox.setInternalState(strategy, "buyFeeFraction", BigDecimal.ZERO);
    Whitebox.setInternalState(strategy, "sellFeeFraction", BigDecimal.ZERO);
    Whitebox.setInternalState(strategy, "feesLoaded", true);
  }

  /**
   * Injects the closed candle history plus the in-progress candle in bucket 10, so a tick with
   * {@link #NEXT_BUCKET_TIMESTAMP} closes {@code inProgress} and recomputes every indicator.
   */
  private void primeForCandleClose(
      MeanReversionRsiBollingerStrategy strategy, List<Object> history, Object inProgress) {
    injectCandles(strategy, history);
    Whitebox.setInternalState(strategy, "currentCandle", inProgress);
    Whitebox.setInternalState(strategy, "currentBucket", 10L);
    Whitebox.setInternalState(strategy, "closedCandleCount", 10L);
  }

  private static void injectCandles(
      MeanReversionRsiBollingerStrategy strategy, List<Object> candles) {
    final Deque<Object> history = Whitebox.getInternalState(strategy, "candleHistory");
    history.clear();
    history.addAll(candles);
  }

  /** Builds an instance of the strategy's private {@code Candle} class. */
  private static Object candle(String open, String high, String low, String close)
      throws Exception {
    final Class<?> candleClass =
        Class.forName("com.gazbert.bxbot.strategies.MeanReversionRsiBollingerStrategy$Candle");
    final Constructor<?> constructor =
        candleClass.getDeclaredConstructor(
            BigDecimal.class, BigDecimal.class, BigDecimal.class, BigDecimal.class);
    constructor.setAccessible(true);
    return constructor.newInstance(bd(open), bd(high), bd(low), bd(close));
  }

  private static BigDecimal candleField(Object candle, String name) throws Exception {
    final Field field = candle.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return (BigDecimal) field.get(candle);
  }

  private static String positionState(MeanReversionRsiBollingerStrategy strategy) {
    return String.valueOf((Object) Whitebox.getInternalState(strategy, "positionState"));
  }

  /** Sets the private {@code positionState} field, resolving the inner enum constant by name. */
  private static void setPositionState(
      MeanReversionRsiBollingerStrategy strategy, String stateName) throws Exception {
    final Field stateField =
        MeanReversionRsiBollingerStrategy.class.getDeclaredField("positionState");
    stateField.setAccessible(true);
    for (final Object constant : stateField.getType().getEnumConstants()) {
      if (constant.toString().equals(stateName)) {
        stateField.set(strategy, constant);
        return;
      }
    }
    throw new IllegalArgumentException("Unknown PositionState: " + stateName);
  }

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }
}
