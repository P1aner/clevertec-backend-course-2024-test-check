package ru.clevertec.check.services;

import ru.clevertec.check.exception.CheckRunnerException;
import ru.clevertec.check.model.DiscountCard;
import ru.clevertec.check.model.Order;
import ru.clevertec.check.model.OrderItem;
import ru.clevertec.check.model.Product;
import ru.clevertec.check.repository.DiscountCardRepositorySqL;
import ru.clevertec.check.repository.ProductRepositorySqL;
import ru.clevertec.check.repository.api.DiscountCardRepository;
import ru.clevertec.check.repository.api.ProductRepository;
import ru.clevertec.check.services.api.OrderItemService;
import ru.clevertec.check.services.api.OrderService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class OrderServiceBase implements OrderService {
    private final OrderItemService orderItemService = new OrderItemServiceBase();
    private final ProductRepository productRepository = new ProductRepositorySqL();
    private final DiscountCardRepository discountCardRepository = new DiscountCardRepositorySqL();

    @Override
    public Order createOrder(Map<Long, Integer> productsArgs, String discountCardId) {
        Map<Product, Integer> countByProduct = productRepository.findByIds(productsArgs.keySet()).stream()
                .collect(Collectors.toMap(Function.identity(), it -> productsArgs.get(it.getId())));

        if (countByProduct.size() != productsArgs.size()) {
            throw new CheckRunnerException("BAD REQUEST");
        }

        List<OrderItem> orderItems = new ArrayList<>();
        countByProduct.forEach((product, count) -> orderItems.add(buildOrderItem(product, count)));

        int discountCardIdInt = Integer.parseInt(discountCardId);
        DiscountCard discountCard = discountCardRepository.findByNumber(discountCardIdInt)
                .stream()
                .findFirst()
                .orElse(null);
        return new Order(orderItems, discountCard);
    }

    private static OrderItem buildOrderItem(Product product, Integer count) {
        if (product.getQuantityInStock() < count) {
            throw new CheckRunnerException("BAD REQUEST");
        }
        return new OrderItem(product, count);
    }

    @Override
    public BigDecimal calculateOrderTotalPrice(Order order) {
        return order.getOrderItems().stream()
                .map(orderItemService::calculateOrderItemTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal calculateOrderTotalDiscount(Order order) {
        return order.getOrderItems().stream()
                .map(orderItem -> orderItemService.calculateOrderItemDiscount(orderItem, order))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal calculateOrderTotalWithDiscount(Order order) {
        return calculateOrderTotalPrice(order)
                .subtract(calculateOrderTotalDiscount(order))
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public boolean isEnoughMoney(Order order, BigDecimal money) {
        if (money.compareTo(calculateOrderTotalWithDiscount(order)) > 0) {
            return true;
        }
        throw new CheckRunnerException("NOT ENOUGH MONEY");
    }

    @Override
    public void completeOrder(Order order, BigDecimal money) {
        isEnoughMoney(order, money);
        List<Long> ids = order.getOrderItems().stream()
                .map(orderItem -> orderItem.getProduct().getId())
                .toList();
        List<Product> byIds = productRepository.findByIds(ids);
        List<Product> products = byIds.stream()
                .peek(product -> {
                    Long productId = product.getId();
                    Integer quantityInStock = product.getQuantityInStock();
                    OrderItem orderItem1 = order.getOrderItems().stream()
                            .filter(orderItem -> orderItem.getProduct().getId().equals(productId))
                            .findFirst()
                            .orElseThrow();
                    quantityInStock -= orderItem1.getCount();
                    product.setQuantityInStock(quantityInStock);
                })
                .toList();
        if (ids.size() == byIds.size() && byIds.size() == products.size()) {
            productRepository.updateAll(products);
        } else throw new CheckRunnerException("");
    }
}