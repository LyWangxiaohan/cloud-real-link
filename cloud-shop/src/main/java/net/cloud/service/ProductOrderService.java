package net.cloud.service;

import net.cloud.controller.request.ConfirmOrderRequest;
import net.cloud.controller.request.ProductOrderPageRequest;
import net.cloud.utils.JsonData;

import java.util.Map;

public interface ProductOrderService {
    Map<String, Object> page(ProductOrderPageRequest productOrderPageRequest);

    String queryProductOrderState(String outTradeNo);

    JsonData confirmOrder(ConfirmOrderRequest request);
}
