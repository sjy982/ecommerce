package com.ecommerce.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.category.model.Category;
import com.ecommerce.category.repository.CategoryRepository;
import com.ecommerce.notification.dto.NotificationResponseDto;
import com.ecommerce.notification.model.Notification;
import com.ecommerce.notification.projection.NotificationProjection;
import com.ecommerce.notification.repository.NotificationRepository;
import com.ecommerce.order.DTO.OrderProductRequestDto;

import com.ecommerce.order.model.Orders;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.store.model.Store;
import com.ecommerce.store.repository.StoreRepository;
import com.ecommerce.user.model.Users;
import com.ecommerce.user.repository.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
class NotificationServiceTest {
    @Autowired
    private OrderService orderService;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationService notificationService;

    private Users user;
    private Product product;

    private Store store;

    @BeforeEach
    void setup() {
        Cart cart = new Cart();
        cartRepository.save(cart);

        user = Users.builder()
                    .subject("testSubject")
                    .providerId("testProviderId")
                    .provider("testProvider")
                    .address("testAddress")
                    .email("testemail")
                    .phone("010-1234-1234")
                    .name("testName")
                    .cart(cart)
                    .build();

        userRepository.save(user);

        store = Store.builder()
                     .name("testName")
                     .password("testPw")
                     .phoneNumber("010-1234-1234")
                     .totalSales(1000L)
                     .build();

        storeRepository.save(store);

        Category category = new Category();
        category.setName("testName");

        categoryRepository.save(category);

        product = Product.builder()
                         .name("testName")
                         .price(100L)
                         .stock(80)
                         .store(store)
                         .category(category)
                         .build();

        productRepository.save(product);
    }

    @Test
    @DisplayName("주문 후 알림이 생성된다. 알림을 확인한 후 읽지 않은 알림을 조회한다면, isRead가 false 알림만이 조회되어야 한다. 또한 내림차순 정렬되어 있어야 한다.")
    @Transactional
    void givenOrderProductAfterCreatedNotification_whenFindByStoreIdAndIsReadFalseOrderByCreatedAtDesc_thenSelectIsReadFalseOrderByCreatedAtDescNotification() {
        // Given
        OrderProductRequestDto requestDto1 = OrderProductRequestDto.builder()
                                                                  .productId(product.getId())
                                                                  .quantity(10)
                                                                  .deliveryAddress("test Address")
                                                                  .phoneNumber("010-1234-1234").build();

        OrderProductRequestDto requestDto2 = OrderProductRequestDto.builder()
                                                                  .productId(product.getId())
                                                                  .quantity(20)
                                                                  .deliveryAddress("test Address")
                                                                  .phoneNumber("010-1234-1234").build();

        OrderProductRequestDto requestDto3 = OrderProductRequestDto.builder()
                                                                   .productId(product.getId())
                                                                   .quantity(30)
                                                                   .deliveryAddress("test Address")
                                                                   .phoneNumber("010-1234-1234").build();

        // When && Then
        placeOrderWithNotification(user, requestDto1);
        placeOrderWithNotification(user, requestDto2);
        placeOrderWithNotification(user, requestDto3);

        List<NotificationProjection> allNotifications = notificationService.getAllNotifications(product.getStore().getId());
        assertEquals(allNotifications.get(0).getCreatedAt().isAfter(allNotifications.get(1).getCreatedAt()), true);

        notificationService.markAsRead(allNotifications.get(2).getId(), store.getId());

        List<NotificationProjection> unReadNotifications = notificationService.getUnReadNotifications(product.getStore().getId());

        assertEquals(unReadNotifications.size(), 2);

        Notification unReadNotification0 = notificationRepository.findById(unReadNotifications.get(0).getId()).get();
        Notification unReadNotification1 = notificationRepository.findById(unReadNotifications.get(1).getId()).get();


        assertEquals(unReadNotification0.getIsRead(), false);
        assertEquals(unReadNotification1.getIsRead(), false);
        assertEquals(unReadNotification0.getOrder().getStore().getId(), product.getStore().getId());
        assertEquals(unReadNotification1.getOrder().getStore().getId(), product.getStore().getId());

        List<Orders> newOrder = orderRepository.findAllByUserProviderIdOrderByOrderDateDesc(user.getProviderId());
        assertEquals(unReadNotification0.getOrder().getId(), newOrder.get(0).getId());
        assertEquals(unReadNotification1.getOrder().getId(), newOrder.get(1).getId());

        assertEquals(unReadNotifications.get(0).getCreatedAt().isAfter(unReadNotifications.get(1).getCreatedAt()), true);
    }

    @Test
    @DisplayName("확인하려는 Notification이 Store의 알림이 아닌 경우 업데이트가 되지 않아야 한다.")
    @Transactional
    void givenStoreIdNotEqualNotificationStoreId_whenMarkAsRead_thenShouldNotUpdate() {
        // Given
        OrderProductRequestDto requestDto1 = OrderProductRequestDto.builder()
                                                                   .productId(product.getId())
                                                                   .quantity(10)
                                                                   .deliveryAddress("test Address")
                                                                   .phoneNumber("010-1234-1234").build();
        // When && Then
        placeOrderWithNotification(user, requestDto1);
        List<NotificationProjection> allNotifications = notificationService.getAllNotifications(product.getStore().getId());
        Notification unReadNotification = notificationRepository.findById(allNotifications.get(0).getId()).get();

        assertThrows(UsernameNotFoundException.class, () ->
                notificationService.markAsRead(
                        unReadNotification.getId(),
                        unReadNotification.getOrder().getStore().getId() + 1
                )
        );
    }

    /** 결제 완료 시 생성되는 알림을 흉내 낸다: 주문 저장 + 알림 생성 */
    private void placeOrderWithNotification(Users orderer, OrderProductRequestDto dto) {
        Orders order = orderRepository.save(Orders.builder()
                .user(orderer).store(store).product(product).quantity(dto.getQuantity())
                .deliveryAddress(dto.getDeliveryAddress()).phoneNumber(dto.getPhoneNumber()).build());
        notificationService.createNotification(order);
        try {
            Thread.sleep(5);   // createdAt 정렬 검증을 위해 생성 시각을 확실히 다르게
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
