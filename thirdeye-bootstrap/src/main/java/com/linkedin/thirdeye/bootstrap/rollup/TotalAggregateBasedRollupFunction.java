package com.linkedin.thirdeye.bootstrap.rollup;

import java.util.Map;
import java.util.Set;

import com.linkedin.thirdeye.api.RollupThresholdFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.thirdeye.api.MetricTimeSeries;
/**
 * 
 * @author kgopalak
 *
 */
public class TotalAggregateBasedRollupFunction implements RollupThresholdFunction
{
  private static final Logger LOG = LoggerFactory
      .getLogger(TotalAggregateBasedRollupFunction.class);
  private String metricName;
  private int totalAggregateThreshold;
  public TotalAggregateBasedRollupFunction(Map<String, String> params){
    this.metricName = params.get("metricName");
    this.totalAggregateThreshold = Integer.parseInt(params.get("threshold"));
  }
  /**
   * 
   */
  @Override
  public boolean isAboveThreshold(MetricTimeSeries timeSeries) {
    Set<Long> timeWindowSet = timeSeries.getTimeWindowSet();
    long sum = 0;
    for (Long timeWindow : timeWindowSet) {
      sum += timeSeries.get(timeWindow, metricName).longValue();
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("sum = " + sum);
    }
    return sum  >= totalAggregateThreshold; 
  }

}
