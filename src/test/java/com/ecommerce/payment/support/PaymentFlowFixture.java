package com.ecommerce.payment.support;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.category.model.Category;
import com.ecommerce.category.repository.CategoryRepository;
import com.ecommerce.notification.projection.NotificationProjection;
import com.ecommerce.notification.repository.NotificationRepository;
import com.ecommerce.order.DTO.OrderProductRequestDto;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.model.Orders;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.payment.job.model.PaymentJob;
import com.ecommerce.payment.job.model.PaymentJobStatus;
import com.ecommerce.payment.job.repository.PaymentJobRepository;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.store.model.Store;
import com.ecommerce.store.repository.StoreRepository;
import com.ecommerce.user.model.Users;
import com.ecommerce.user.repository.UserRepository;

/**
 * 커밋 이후(AFTER_COMMIT) 흐름을 검증하려면 테스트 트랜잭션을 쓸 수 없어서,
 * 고유한 이름으로 데이터를 만들고 끝나면 직접 지운다.
 */
public class PaymentFlowFixture {
    public static final long PRICE = 100L;

    private final CartRepository cartRepository;
    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final PaymentJobRepository paymentJobRepository;
    private final NotificationRepository notificationRepository;

    private Cart cart;
    private Users user;
    private Store store;
    private Category category;
    private Product product;

    public PaymentFlowFixture(CartRepository cartRepository, UserRepository userRepository,
                              StoreRepository storeRepository, CategoryRepository categoryRepository,
                              ProductRepository productRepository, OrderRepository orderRepository,
                              PaymentJobRepository paymentJobRepository,
                              NotificationRepository notificationRepository) {
        this.cartRepository = cartRepository;
        this.userRepository = userRepository;
        this.storeRepository = storeRepository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.paymentJobRepository = paymentJobRepository;
        this.notificationRepository = notificationRepository;
    }

    public void setUp(int stock) {
        String key = UUID.randomUUID().toString().substring(0, 8);
        cart = new Cart();   // Users.cart는 CascadeType.ALL이라 user 저장 시 함께 저장된다
        user = userRepository.save(Users.builder()
                .subject("subject-" + key)
                .providerId("provider-" + key)
                .provider("test")
                .address("address")
                .email("payment-flow-" + key + "@test.com")
                .phone("010-1234-1234")
                .name("name")
                .cart(cart)
                .build());
        store = storeRepository.save(Store.builder()
                .name("store-" + key)
                .password("pw")
                .phoneNumber("010-1234-1234")
                .totalSales(0L)
                .build());
        Category newCategory = new Category();
        newCategory.setName("category-" + key);
        category = categoryRepository.save(newCategory);
        product = productRepository.save(Product.builder()
                .name("product-" + key)
                .price(PRICE)
                .stock(stock)
                .store(store)
                .category(category)
                .build());
    }

    public String providerId() {
        return user.getProviderId();
    }

    public Long storeId() {
        return store.getId();
    }

    public String storeName() {
        return store.getName();
    }

    public Long productId() {
        return product.getId();
    }

    /** DB에 저장된 product.stock (판매 총수량). 주문 접수로는 바뀌지 않아야 한다. */
    public int productStockInDb() {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }

    public OrderProductRequestDto orderRequest() {
        return OrderProductRequestDto.builder()
                .productId(product.getId())
                .quantity(1)
                .deliveryAddress("address")
                .phoneNumber("010-1234-1234")
                .build();
    }

    /** 빠른 경로를 거치지 않은(놓친) 결제 작업: 주문과 PENDING job만 DB에 있는 상태. */
    public Orders createMissedPendingOrder() {
        Orders order = orderRepository.save(Orders.builder()
                .user(user)
                .store(store)
                .product(product)
                .quantity(1)
                .deliveryAddress("address")
                .phoneNumber("010-1234-1234")
                .status(OrderStatus.PENDING_PAYMENT)
                .build());
        paymentJobRepository.save(PaymentJob.builder()
                .orderId(order.getId())
                .status(PaymentJobStatus.PENDING)
                .build());
        return order;
    }

    public OrderStatus orderStatus(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    public long totalSales() {
        return storeRepository.findById(store.getId()).orElseThrow().getTotalSales();
    }

    public boolean paymentJobExists(Long orderId) {
        return paymentJobRepository.findAll().stream().anyMatch(job -> orderId.equals(job.getOrderId()));
    }

    public List<Orders> orders() {
        return orderRepository.findAllByStoreIdOrderByOrderDateDesc(store.getId());
    }

    public static boolean await(Supplier<Boolean> condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.get();
    }

    public void cleanUp() {
        if (store == null) {
            return;
        }
        for (NotificationProjection notification : notificationRepository.findNotificationsByStoreId(store.getId())) {
            notificationRepository.deleteById(notification.getId());
        }
        List<Orders> orders = orders();
        List<Long> orderIds = orders.stream().map(Orders::getId).toList();
        paymentJobRepository.deleteAll(paymentJobRepository.findAll().stream()
                .filter(job -> orderIds.contains(job.getOrderId()))
                .toList());
        orderRepository.deleteAll(orders);
        productRepository.delete(product);
        categoryRepository.delete(category);
        storeRepository.delete(store);
        userRepository.delete(user);   // cart는 cascade로 함께 삭제
    }
}
