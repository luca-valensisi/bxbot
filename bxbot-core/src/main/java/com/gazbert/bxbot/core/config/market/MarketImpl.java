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

package com.gazbert.bxbot.core.config.market;

import com.gazbert.bxbot.trading.api.Market;
import com.google.common.base.Objects;
import lombok.Setter;
import lombok.ToString;

/**
 * Holds information for an Exchange market.
 *
 * @author gazbert
 */
@Setter
@ToString
public final class MarketImpl implements Market {

  private String name;
  private String id;
  private String baseCurrency;
  private String counterCurrency;

  /**
   * Creates a new MarketImpl.
   *
   * @param name the name of the market.
   * @param id the ID of the market.
   * @param baseCurrency the market base currency.
   * @param counterCurrency the market counter currency.
   */
  public MarketImpl(String name, String id, String baseCurrency, String counterCurrency) {
    this.id = id;
    this.name = name;
    this.baseCurrency = baseCurrency;
    this.counterCurrency = counterCurrency;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void setId(String id) {
    this.id = id;
  }

  @Override
  public String getId() {
    return id;
  }

  /**
   * Sets the base currency.
   *
   * @param baseCurrency the base currency.
   */
  void setBaseCurrency(String baseCurrency) {
    this.baseCurrency = baseCurrency;
  }

  @Override
  public String getBaseCurrency() {
    return baseCurrency;
  }

  /**
   * Sets the counter currency.
   *
   * @param counterCurrency the counter currency.
   */
  void setCounterCurrency(String counterCurrency) {
    this.counterCurrency = counterCurrency;
  }

  @Override
  public String getCounterCurrency() {
    return counterCurrency;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    MarketImpl market = (MarketImpl) o;
    return Objects.equal(id, market.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
