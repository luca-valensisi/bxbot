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
import static org.junit.Assert.assertThrows;

import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.OpenOrder;
import com.gazbert.bxbot.trading.api.Ticker;
import com.gazbert.bxbot.trading.api.TradingApi;
import com.gazbert.bxbot.trading.api.TradingApiException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.powermock.reflect.Whitebox;

/**
 * Tests the Moving Average Channel Breakout Strategy.
 *
 * @author luca valensisi
 */
public class TestMovingAverageChannelBreakoutStrategy {

  private static final String MARKET_ID = "btc_usd";
  private static final String MARKET_NAME = "BTC/USD";
  private static final String BASE_CURRENCY = "BTC";
  private static final String COUNTER_CURRENCY = "USD";

  // Minimal config — short=3, long=5, channel=5, so warm-up = 5 ticks
  private static final String SHORT_EMA = "3";
  private static final String LONG_EMA = "5";
  private static final String CHANNEL = "5";
  private static final String STOP_LOSS = "2.0";
  private static final String BUY_AMOUNT = "20";

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
    expect(config.getConfigItem("counter-currency-buy-order-amount")).andReturn(null);
    replay(config, tradingApi, market);

    final MovingAverageChannelBreakoutStrategy strategy =
        new MovingAverageChannelBreakoutStrategy();
    assertThrows(
        IllegalArgumentException.class, () -> strategy.init(tradingApi, market, config));
    verify(config, tradingApi, market);
  }

  @Test
  public void testShortEmaMustBeLessThanLongEmaThrows() {
    setupConfigExpectations("5", "3", CHANNEL, STOP_LOSS, "true"); // short >= long → illegal
    replay(config, tradingApi, market);

    final MovingAverageChannelBreakoutStrategy strategy =
        new MovingAverageChannelBreakoutStrategy();
    assertThrows(
        IllegalArgumentException.class, () -> strategy.init(tradingApi, market, config));
  }

  // ---------------------------------------------------------------------------
  // Warm-up phase
  // ---------------------------------------------------------------------------

  @Test
  public void testWarmUpPhaseDoesNotPlaceOrders() throws Exception {
    setupConfigExpectations(SHORT_EMA, LONG_EMA, CHANNEL, STOP_LOSS, "true");
    expect(market.getName()).andStubReturn(MARKET_NAME);
    expect(market.getId()).andStubReturn(MARKET_ID);

    // Return a steadily rising price for fewer ticks than the warm-up period (5)
    final Ticker ticker = createMock(Ticker.class);
    expect(ticker.getLast()).andReturn(new BigDecimal("45000")).times(4);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker).times(4);

    replay(config, tradingApi, market, ticker);

    final MovingAverageChannelBreakoutStrategy strategy = buildAndInitStrategy("true");
    // 4 cycles — warm-up not complete, createOrder must never be called
    for (int i = 0; i < 4; i++) {
      strategy.execute();
    }
    verify(config, tradingApi, market, ticker);
  }

  // ---------------------------------------------------------------------------
  // Test mode — BUY signal
  // ---------------------------------------------------------------------------

  @Test
  public void testTestModeBuySignalLogsAndTransitionsWithoutCreateOrder() throws Exception {
    setupConfigExpectations(SHORT_EMA, LONG_EMA, CHANNEL, STOP_LOSS, "true");
    expect(market.getName()).andStubReturn(MARKET_NAME);
    expect(market.getId()).andStubReturn(MARKET_ID);
    expect(market.getBaseCurrency()).andStubReturn(BASE_CURRENCY);
    expect(market.getCounterCurrency()).andStubReturn(COUNTER_CURRENCY);

    final Ticker ticker = createMock(Ticker.class);

    // Feed 5 rising prices to complete warm-up and trigger a BUY breakout on tick 5.
    // Prices: 100, 110, 120, 130, 140 — a clear uptrend; tick 5 = upper channel
    final BigDecimal[] prices = {
      bd("100"), bd("110"), bd("120"), bd("130"), bd("140")
    };
    for (final BigDecimal p : prices) {
      expect(ticker.getLast()).andReturn(p);
    }
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker).times(5);
    // getAmountOfBaseCurrencyToBuy needs latest market price
    expect(tradingApi.getLatestMarketPrice(MARKET_ID)).andReturn(bd("140"));

    replay(config, tradingApi, market, ticker);

    final MovingAverageChannelBreakoutStrategy strategy = buildAndInitStrategy("true");
    for (int i = 0; i < 5; i++) {
      strategy.execute();
    }
    // createOrder must NOT have been called — verified by EasyMock
    verify(config, tradingApi, market, ticker);
  }

  // ---------------------------------------------------------------------------
  // Live mode — BUY signal places real order
  // ---------------------------------------------------------------------------

  @Test
  public void testLiveModeBuySignalCallsCreateOrder() throws Exception {
    setupConfigExpectations(SHORT_EMA, LONG_EMA, CHANNEL, STOP_LOSS, "false");
    expect(market.getName()).andStubReturn(MARKET_NAME);
    expect(market.getId()).andStubReturn(MARKET_ID);
    expect(market.getBaseCurrency()).andStubReturn(BASE_CURRENCY);
    expect(market.getCounterCurrency()).andStubReturn(COUNTER_CURRENCY);

    final Ticker ticker = createMock(Ticker.class);
    final BigDecimal[] prices = {
      bd("100"), bd("110"), bd("120"), bd("130"), bd("140")
    };
    for (final BigDecimal p : prices) {
      expect(ticker.getLast()).andReturn(p);
    }
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker).times(5);
    expect(tradingApi.getLatestMarketPrice(MARKET_ID)).andReturn(bd("140"));

    // The strategy places a real BUY order
    final BigDecimal expectedAmount =
        bd("20").divide(bd("140"), 8, java.math.RoundingMode.HALF_DOWN);
    expect(tradingApi.createOrder(
            MARKET_ID,
            com.gazbert.bxbot.trading.api.OrderType.BUY,
            expectedAmount,
            bd("140")))
        .andReturn("ORDER-123");

    replay(config, tradingApi, market, ticker);

    final MovingAverageChannelBreakoutStrategy strategy = buildAndInitStrategy("false");
    for (int i = 0; i < 5; i++) {
      strategy.execute();
    }
    verify(config, tradingApi, market, ticker);
  }

  // ---------------------------------------------------------------------------
  // Stop-loss in test mode
  // ---------------------------------------------------------------------------

  @Test
  public void testStopLossClosesPositionInTestMode() throws Exception {
    setupConfigExpectations(SHORT_EMA, LONG_EMA, CHANNEL, STOP_LOSS, "true");
    expect(market.getName()).andStubReturn(MARKET_NAME);
    expect(market.getId()).andStubReturn(MARKET_ID);
    expect(market.getBaseCurrency()).andStubReturn(BASE_CURRENCY);
    expect(market.getCounterCurrency()).andStubReturn(COUNTER_CURRENCY);

    final Ticker ticker = createMock(Ticker.class);

    // Ticks 1-5: rising prices — triggers BUY on tick 5
    final BigDecimal[] setupPrices = {bd("100"), bd("110"), bd("120"), bd("130"), bd("140")};
    for (final BigDecimal p : setupPrices) {
      expect(ticker.getLast()).andReturn(p);
    }
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker).times(5);
    expect(tradingApi.getLatestMarketPrice(MARKET_ID)).andReturn(bd("140"));

    // Tick 6: price crashes below stop-loss level (140 * (1 - 0.02) = 137.20)
    expect(ticker.getLast()).andReturn(bd("130"));
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker);

    replay(config, tradingApi, market, ticker);

    final MovingAverageChannelBreakoutStrategy strategy = buildAndInitStrategy("true");
    for (int i = 0; i < 6; i++) {
      strategy.execute();
    }
    // No createOrder call expected (test mode); verify all mocks satisfied
    verify(config, tradingApi, market, ticker);
  }

  // ---------------------------------------------------------------------------
  // Exception handling
  // ---------------------------------------------------------------------------

  @Test
  public void testNetworkExceptionIsSwallowed() throws Exception {
    setupConfigExpectations(SHORT_EMA, LONG_EMA, CHANNEL, STOP_LOSS, "true");
    expect(market.getName()).andStubReturn(MARKET_NAME);
    expect(market.getId()).andStubReturn(MARKET_ID);

    expect(tradingApi.getTicker(MARKET_ID))
        .andThrow(new ExchangeNetworkException("Simulated network error"));

    replay(config, tradingApi, market);

    final MovingAverageChannelBreakoutStrategy strategy = buildAndInitStrategy("true");
    // Must NOT throw — network errors are swallowed
    strategy.execute();
    verify(config, tradingApi, market);
  }

  @Test
  public void testTradingApiExceptionWrapsToStrategyException() throws Exception {
    setupConfigExpectations(SHORT_EMA, LONG_EMA, CHANNEL, STOP_LOSS, "true");
    expect(market.getName()).andStubReturn(MARKET_NAME);
    expect(market.getId()).andStubReturn(MARKET_ID);

    expect(tradingApi.getTicker(MARKET_ID))
        .andThrow(new TradingApiException("Simulated API error"));

    replay(config, tradingApi, market);

    final MovingAverageChannelBreakoutStrategy strategy = buildAndInitStrategy("true");
    assertThrows(StrategyException.class, strategy::execute);
    verify(config, tradingApi, market);
  }

  // ---------------------------------------------------------------------------
  // State-injection tests (cover WAITING_* paths and sell-signal branch)
  // ---------------------------------------------------------------------------

  @Test
  public void testNullTickerSkipsCycle() throws Exception {
    setupConfigExpectations(SHORT_EMA, LONG_EMA, CHANNEL, STOP_LOSS, "true");
    expect(market.getId()).andStubReturn(MARKET_ID);
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(null);
    replay(config, tradingApi, market);

    final MovingAverageChannelBreakoutStrategy strategy = buildAndInitStrategy("true");
    strategy.execute(); // must not throw
    verify(config, tradingApi, market);
  }

  @Test
  public void testHandleWaitingBuyFillOrderFilled() throws Exception {
    setupConfigExpectations(SHORT_EMA, LONG_EMA, CHANNEL, STOP_LOSS, "true");
    expect(market.getId()).andStubReturn(MARKET_ID);

    final Ticker ticker = createMock(Ticker.class);
    expect(ticker.getLast()).andReturn(bd("45000"));
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker);
    expect(tradingApi.getYourOpenOrders(MARKET_ID)).andReturn(Collections.emptyList());

    replay(config, tradingApi, market, ticker);

    final MovingAverageChannelBreakoutStrategy strategy = buildAndInitStrategy("true");
    injectWarmUpState(strategy);
    injectPositionState(strategy, "WAITING_BUY_FILL");
    Whitebox.setInternalState(strategy, "activeOrderId", "TEST-BUY-1");

    strategy.execute();
    verify(config, tradingApi, market, ticker);
  }

  @Test
  public void testHandleWaitingBuyFillOrderStillOpen() throws Exception {
    setupConfigExpectations(SHORT_EMA, LONG_EMA, CHANNEL, STOP_LOSS, "true");
    expect(market.getId()).andStubReturn(MARKET_ID);

    final Ticker ticker = createMock(Ticker.class);
    expect(ticker.getLast()).andReturn(bd("45000"));
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker);

    final OpenOrder openOrder = createMock(OpenOrder.class);
    expect(openOrder.getId()).andReturn("TEST-BUY-1");
    final List<OpenOrder> openOrders = new ArrayList<>();
    openOrders.add(openOrder);
    expect(tradingApi.getYourOpenOrders(MARKET_ID)).andReturn(openOrders);

    replay(config, tradingApi, market, ticker, openOrder);

    final MovingAverageChannelBreakoutStrategy strategy = buildAndInitStrategy("true");
    injectWarmUpState(strategy);
    injectPositionState(strategy, "WAITING_BUY_FILL");
    Whitebox.setInternalState(strategy, "activeOrderId", "TEST-BUY-1");

    strategy.execute();
    verify(config, tradingApi, market, ticker, openOrder);
  }

  @Test
  public void testHandleLongSellSignalInTestMode() throws Exception {
    setupConfigExpectations(SHORT_EMA, LONG_EMA, CHANNEL, STOP_LOSS, "true");
    expect(market.getId()).andStubReturn(MARKET_ID);
    expect(market.getBaseCurrency()).andStubReturn(BASE_CURRENCY);
    expect(market.getCounterCurrency()).andStubReturn(COUNTER_CURRENCY);

    // Price 80 sits at the lower Donchian bound; after EMA update short(85) < long(100) → SELL
    final Ticker ticker = createMock(Ticker.class);
    expect(ticker.getLast()).andReturn(bd("80"));
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker);

    replay(config, tradingApi, market, ticker);

    final MovingAverageChannelBreakoutStrategy strategy = buildAndInitStrategy("true");
    // priceHistory [100,95,90,85,80] → lowerChannel = 80
    final Deque<BigDecimal> history = new ArrayDeque<>(
        List.of(bd("100"), bd("95"), bd("90"), bd("85"), bd("80")));
    Whitebox.setInternalState(strategy, "priceHistory", history);
    // short=90, long=110 → after ema(80): short=85, long=100 → sell condition
    Whitebox.setInternalState(strategy, "shortEmaValue", bd("90"));
    Whitebox.setInternalState(strategy, "longEmaValue", bd("110"));
    injectPositionState(strategy, "LONG");
    Whitebox.setInternalState(strategy, "entryPrice", bd("79")); // stop-loss at 77.42
    Whitebox.setInternalState(strategy, "activeOrderAmount", bd("0.1"));

    strategy.execute(); // [TEST MODE] SELL logged; no createOrder called
    verify(config, tradingApi, market, ticker);
  }

  @Test
  public void testHandleWaitingSellFillOrderFilled() throws Exception {
    setupConfigExpectations(SHORT_EMA, LONG_EMA, CHANNEL, STOP_LOSS, "true");
    expect(market.getId()).andStubReturn(MARKET_ID);

    final Ticker ticker = createMock(Ticker.class);
    expect(ticker.getLast()).andReturn(bd("45000"));
    expect(tradingApi.getTicker(MARKET_ID)).andReturn(ticker);
    expect(tradingApi.getYourOpenOrders(MARKET_ID)).andReturn(Collections.emptyList());

    replay(config, tradingApi, market, ticker);

    final MovingAverageChannelBreakoutStrategy strategy = buildAndInitStrategy("true");
    injectWarmUpState(strategy);
    injectPositionState(strategy, "WAITING_SELL_FILL");
    Whitebox.setInternalState(strategy, "activeOrderId", "TEST-SELL-1");
    Whitebox.setInternalState(strategy, "activeOrderAmount", bd("0.1"));
    Whitebox.setInternalState(strategy, "entryPrice", bd("45000"));

    strategy.execute();
    verify(config, tradingApi, market, ticker);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private MovingAverageChannelBreakoutStrategy buildAndInitStrategy(String testModeValue) {
    final MovingAverageChannelBreakoutStrategy strategy =
        new MovingAverageChannelBreakoutStrategy();
    strategy.init(tradingApi, market, config);
    return strategy;
  }

  private void setupConfigExpectations(
      String shortEma, String longEma, String channel, String stopLoss, String testModeValue) {
    expect(config.getConfigItem("counter-currency-buy-order-amount")).andReturn(BUY_AMOUNT);
    expect(config.getConfigItem("short-ema-period")).andReturn(shortEma);
    expect(config.getConfigItem("long-ema-period")).andReturn(longEma);
    expect(config.getConfigItem("channel-period")).andReturn(channel);
    expect(config.getConfigItem("stop-loss-percentage")).andReturn(stopLoss);
    expect(config.getConfigItem("test-mode")).andReturn(testModeValue);
    expect(market.getName()).andStubReturn(MARKET_NAME);
  }

  private static BigDecimal bd(String val) {
    return new BigDecimal(val);
  }

  /**
   * Injects a completed warm-up state: priceHistory with 5 neutral prices and both EMAs set.
   * After one more call to execute(), the strategy will see warm-up as complete.
   */
  private static void injectWarmUpState(MovingAverageChannelBreakoutStrategy strategy) {
    final Deque<BigDecimal> history = new ArrayDeque<>(
        List.of(bd("100"), bd("101"), bd("102"), bd("103"), bd("104")));
    Whitebox.setInternalState(strategy, "priceHistory", history);
    Whitebox.setInternalState(strategy, "shortEmaValue", bd("102"));
    Whitebox.setInternalState(strategy, "longEmaValue", bd("101"));
  }

  /**
   * Sets the private {@code positionState} field via reflection, resolving the enum constant by
   * name from the strategy's inner {@code PositionState} enum.
   */
  private static void injectPositionState(
      MovingAverageChannelBreakoutStrategy strategy, String stateName) throws Exception {
    final Field stateField =
        MovingAverageChannelBreakoutStrategy.class.getDeclaredField("positionState");
    stateField.setAccessible(true);
    for (Object constant : stateField.getType().getEnumConstants()) {
      if (constant.toString().equals(stateName)) {
        stateField.set(strategy, constant);
        return;
      }
    }
    throw new IllegalArgumentException("Unknown PositionState: " + stateName);
  }
}
