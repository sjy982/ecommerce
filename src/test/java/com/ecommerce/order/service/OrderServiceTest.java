package com.ecommerce.order.service;

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
import com.ecommerce.notification.model.Notification;
import com.ecommerce.notification.projection.NotificationProjection;
import com.ecommerce.notification.repository.NotificationRepository;
import com.ecommerce.order.DTO.OrderProductRequestDto;
import com.ecommerce.order.DTO.OrderProductResponseDto;
import com.ecommerce.order.model.Orders;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.store.model.Store;
import com.ecommerce.store.repository.StoreRepository;
import com.ecommerce.user.model.Users;
import com.ecommerce.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest
@ActiveProfiles("test")
class OrderServiceTest {
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

    @PersistenceContext
    private EntityManager entityManager;

    private Users user;
    private Users user2;
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

        Cart cart2 = new Cart();
        cartRepository.save(cart2);

        user2 = Users.builder()
                    .subject("testSubject2")
                    .providerId("testProviderId2")
                    .provider("testProvider2")
                    .address("testAddress2")
                    .email("testemail2")
                    .phone("010-1234-1231")
                    .name("testName2")
                    .cart(cart2)
                    .build();

        userRepository.save(user2);

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
                         .stock(100)
                         .store(store)
                         .category(category)
                         .build();

        productRepository.save(product);
    }

    @Test
    @DisplayName("유저가 주문 내용을 조회할 때 주문의 userId와 유저의 userId가 같은 경우 조회가 가능해야 한다.")
    @Transactional
    void givenOrderUserIdEqualUserId_whenGetUserOrderProductResponseDto_thenShouldReturnOrderProductResponseDto() {
        // Given
        OrderProductRequestDto requestDto = OrderProductRequestDto.builder()
                                                                  .productId(product.getId())
                                                                  .quantity(10)
                                                                  .deliveryAddress("test Address")
                                                                  .phoneNumber("010-1234-1234").build();

        // When
        OrderProductResponseDto orderProductResponseDto = placeOrder(user, requestDto);
        List<Orders> orders = orderRepository.findAllByUserProviderIdOrderByOrderDateDesc(user.getProviderId());

        OrderProductResponseDto responseDto = orderService.getUserOrderProductResponseDto(orders.get(0).getId(), user.getProviderId());

        // Then
        assertEquals(responseDto.getOrderProduct().getName(), orderProductResponseDto.getOrderProduct().getName());
        assertEquals(responseDto.getOrderProduct().getQuantity(), orderProductResponseDto.getOrderProduct().getQuantity());
        assertEquals(responseDto.getOrderProduct().getPrice(), orderProductResponseDto.getOrderProduct().getPrice());
        assertEquals(responseDto.getPhoneNumber(), orderProductResponseDto.getPhoneNumber());
        assertEquals(responseDto.getDeliveryAddress(), orderProductResponseDto.getDeliveryAddress());
    }

    @Test
    @DisplayName("유저가 주문 내용을 조회할 때 주문의 userId와 유저의 userId가 같지 않은 경우 NotFound 예외가 발생해야 한다.")
    @Transactional
    void givenOrderUserIdNotEqualUserId_whenGetUserOrderProductResponseDto_thenShouldThrowsNotFoundException() {
        // Given
        OrderProductRequestDto requestDto = OrderProductRequestDto.builder()
                                                                  .productId(product.getId())
                                                                  .quantity(10)
                                                                  .deliveryAddress("test Address")
                                                                  .phoneNumber("010-1234-1234").build();

        // When && Then
        placeOrder(user, requestDto);
        List<Orders> orders = orderRepository.findAllByUserProviderIdOrderByOrderDateDesc(user.getProviderId());

        assertThrows(UsernameNotFoundException.class, () -> orderService.getUserOrderProductResponseDto(orders.get(0).getId(), "noneUser"));
    }

    @Test
    @DisplayName("유효한 유저는 자신의 모든 주문 내용을 조회할 수 있다.")
    @Transactional
    void givenValidUser_whenGetUserAllOrderProductsResponseDto_thenShouldReturnOrderProducts() {
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

        // When
        OrderProductResponseDto orderProductResponseDto1 = placeOrder(user, requestDto1);
        OrderProductResponseDto orderProductResponseDto2 = placeOrder(user, requestDto2);
        placeOrder(user2, requestDto2);

        List<OrderProductResponseDto> orderProductResponseDtoList = orderService.getUserAllOrderProductsResponseDto(user.getProviderId());
        // Then
        assertEquals(orderProductResponseDtoList.size(), 2);
        assertEquals(orderProductResponseDtoList.get(1).getOrderProduct().getQuantity(), orderProductResponseDto1.getOrderProduct().getQuantity());
        assertEquals(orderProductResponseDtoList.get(0).getOrderProduct().getQuantity(), orderProductResponseDto2.getOrderProduct().getQuantity());
        assertEquals(orderProductResponseDtoList.get(1).getOrderProduct().getName(), orderProductResponseDto1.getOrderProduct().getName());
        assertEquals(orderProductResponseDtoList.get(0).getOrderProduct().getName(), orderProductResponseDto2.getOrderProduct().getName());
        assertEquals(orderProductResponseDtoList.get(1).getOrderProduct().getPrice(), orderProductResponseDto1.getOrderProduct().getPrice());
        assertEquals(orderProductResponseDtoList.get(0).getOrderProduct().getPrice(), orderProductResponseDto2.getOrderProduct().getPrice());
        assertEquals(orderProductResponseDtoList.get(1).getPhoneNumber(), orderProductResponseDto1.getPhoneNumber());
        assertEquals(orderProductResponseDtoList.get(0).getPhoneNumber(), orderProductResponseDto2.getPhoneNumber());
        assertEquals(orderProductResponseDtoList.get(1).getDeliveryAddress(), orderProductResponseDto1.getDeliveryAddress());
        assertEquals(orderProductResponseDtoList.get(0).getDeliveryAddress(), orderProductResponseDto2.getDeliveryAddress());
    }

    @Test
    @DisplayName("상점 주인이 주문 내용을 조회할 때 주문의 storeId와 상점의 storeId가 같은 경우 조회가 가능해야 한다.")
    @Transactional
    void givenOrderStoreIdEqualStoreId_whenGetStoreOrderProductResponseDto_thenShouldReturnOrderProductResponseDto() {
        // Given
        OrderProductRequestDto requestDto = OrderProductRequestDto.builder()
                                                                  .productId(product.getId())
                                                                  .quantity(10)
                                                                  .deliveryAddress("test Address")
                                                                  .phoneNumber("010-1234-1234").build();

        // When
        OrderProductResponseDto orderProductResponseDto = placeOrder(user, requestDto);
        List<Orders> orders = orderRepository.findAllByUserProviderIdOrderByOrderDateDesc(user.getProviderId());

        OrderProductResponseDto responseDto = orderService.getStoreOrderProductResponseDto(orders.get(0).getId(), product.getStore().getId());

        // Then
        assertEquals(responseDto.getOrderProduct().getName(), orderProductResponseDto.getOrderProduct().getName());
        assertEquals(responseDto.getOrderProduct().getQuantity(), orderProductResponseDto.getOrderProduct().getQuantity());
        assertEquals(responseDto.getOrderProduct().getPrice(), orderProductResponseDto.getOrderProduct().getPrice());
        assertEquals(responseDto.getPhoneNumber(), orderProductResponseDto.getPhoneNumber());
        assertEquals(responseDto.getDeliveryAddress(), orderProductResponseDto.getDeliveryAddress());
    }

    @Test
    @DisplayName("상점 주인이 주문 내용을 조회할 때 주문의 storeId와 상점의 storeId가 같지 않은 경우 NotFound 예외가 발생해야 한다.")
    @Transactional
    void givenOrderStoreIdNotEqualStoreId_whenGetStoreOrderProductResponseDto_thenShouldThrowsNotFoundException() {
        // Given
        OrderProductRequestDto requestDto = OrderProductRequestDto.builder()
                                                                  .productId(product.getId())
                                                                  .quantity(10)
                                                                  .deliveryAddress("test Address")
                                                                  .phoneNumber("010-1234-1234").build();

        // When && Then
        placeOrder(user, requestDto);
        List<Orders> orders = orderRepository.findAllByUserProviderIdOrderByOrderDateDesc(user.getProviderId());

        assertThrows(UsernameNotFoundException.class, () -> orderService.getStoreOrderProductResponseDto(orders.get(0).getId(), product.getStore().getId() + 1));
    }

    @Test
    @DisplayName("유효한 상점 주인은 자신의 모든 주문 내용을 조회할 수 있다.")
    @Transactional
    void givenValidStore_whenGetStoreAllOrderProductsResponseDto_thenShouldReturnOrderProducts() {
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

        // When
        OrderProductResponseDto orderProductResponseDto1 = placeOrder(user, requestDto1);
        OrderProductResponseDto orderProductResponseDto2 = placeOrder(user, requestDto2);

        List<OrderProductResponseDto> orderProductResponseDtoList = orderService.getStoreAllOrderProductsResponseDto(product.getStore().getId());
        // Then
        assertEquals(orderProductResponseDtoList.size(), 2);
        assertEquals(orderProductResponseDtoList.get(1).getOrderProduct().getQuantity(), orderProductResponseDto1.getOrderProduct().getQuantity());
        assertEquals(orderProductResponseDtoList.get(0).getOrderProduct().getQuantity(), orderProductResponseDto2.getOrderProduct().getQuantity());
        assertEquals(orderProductResponseDtoList.get(1).getOrderProduct().getName(), orderProductResponseDto1.getOrderProduct().getName());
        assertEquals(orderProductResponseDtoList.get(0).getOrderProduct().getName(), orderProductResponseDto2.getOrderProduct().getName());
        assertEquals(orderProductResponseDtoList.get(1).getOrderProduct().getPrice(), orderProductResponseDto1.getOrderProduct().getPrice());
        assertEquals(orderProductResponseDtoList.get(0).getOrderProduct().getPrice(), orderProductResponseDto2.getOrderProduct().getPrice());
        assertEquals(orderProductResponseDtoList.get(1).getPhoneNumber(), orderProductResponseDto1.getPhoneNumber());
        assertEquals(orderProductResponseDtoList.get(0).getPhoneNumber(), orderProductResponseDto2.getPhoneNumber());
        assertEquals(orderProductResponseDtoList.get(1).getDeliveryAddress(), orderProductResponseDto1.getDeliveryAddress());
        assertEquals(orderProductResponseDtoList.get(0).getDeliveryAddress(), orderProductResponseDto2.getDeliveryAddress());
    }

    /** 조회 테스트용 주문을 DB에 바로 저장한다. (주문 접수 흐름 자체는 OrderStockIntegrationTest에서 검증) */
    private OrderProductResponseDto placeOrder(Users orderer, OrderProductRequestDto dto) {
        Orders order = orderRepository.save(Orders.builder()
                .user(orderer).store(store).product(product).quantity(dto.getQuantity())
                .deliveryAddress(dto.getDeliveryAddress()).phoneNumber(dto.getPhoneNumber()).build());
        return orderService.getUserOrderProductResponseDto(order.getId(), orderer.getProviderId());
    }
}
