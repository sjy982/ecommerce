package com.ecommerce.cart.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.cart.DTO.AddItemToCartRequestDto;
import com.ecommerce.cart.DTO.AddItemToCartResponseDto;
import com.ecommerce.cart.DTO.CartItemResponseDto;
import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.cartItem.model.CartItem;
import com.ecommerce.cartItem.projection.CartItemProjection;
import com.ecommerce.cartItem.repository.CartItemRepository;
import com.ecommerce.category.model.Category;
import com.ecommerce.category.repository.CategoryRepository;
import com.ecommerce.order.DTO.OrderProductDto;
import com.ecommerce.order.model.Orders;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.ProductRepository;

import com.ecommerce.store.model.Store;
import com.ecommerce.store.repository.StoreRepository;
import com.ecommerce.user.model.Users;
import com.ecommerce.user.repository.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
class CartServiceTest {
    @Autowired
    private CartService cartService;

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
    private CartItemRepository cartItemRepository;

    private Users user;
    private Users user2;

    private Product product;

    private Product product2;


    @BeforeEach
    @Transactional
    void setup() {
        Cart cart = new Cart();
        Cart cart2 = new Cart();

        cartRepository.save(cart);
        cartRepository.save(cart2);

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

        user2 = Users.builder()
                    .subject("testSubject2")
                    .providerId("testProviderId2")
                    .provider("testProvider")
                    .address("testAddress")
                    .email("testemail2")
                    .phone("010-1234-1234")
                    .name("testName")
                    .cart(cart2)
                    .build();

        userRepository.save(user);
        userRepository.save(user2);

        Store store = Store.builder()
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
                         .stock(50)
                         .store(store)
                         .category(category)
                         .build();

         product2 = Product.builder()
                         .name("testName2")
                         .price(100L)
                         .stock(50)
                         .store(store)
                         .category(category)
                         .build();

        productRepository.save(product);
        productRepository.save(product2);
    }

    @Test
    @DisplayName("유저가 상품을 카트에 담을 때 재고가 충분하다면 담을 수 있어야 한다.")
    @Transactional
    void givenAddItemToCartRequestDto_whenAddItemToCart_thenSuccessAdd() {
        // Given
        AddItemToCartRequestDto requestDto = AddItemToCartRequestDto.builder()
                .productId(product.getId())
                .quantity(10).build();

        // When
        AddItemToCartResponseDto responseDto = cartService.addItemToCart(user.getProviderId(), requestDto);

        // Then
        assertEquals(product.getName(), responseDto.getProductName());

        CartItem newCartItem = cartItemRepository.findByCartId(user.getCart().getId()).get();

        assertEquals(newCartItem.getProduct().getName(), responseDto.getProductName());
        assertEquals(newCartItem.getQuantity(), responseDto.getQuantity());
    }

    @Test
    @DisplayName("유저가 상품을 카트에 담을 때 재고가 충분하지 않다면 담지 못해야 한다.")
    @Transactional
    void givenOutOfStockAddItemToCartRequestDto_whenAddItemToCart_thenFailedAdd() {
        // Given
        AddItemToCartRequestDto requestDto = AddItemToCartRequestDto.builder()
                                                                    .productId(product.getId())
                                                                    .quantity(60).build();

        // When && Then
        assertThrows(UsernameNotFoundException.class, () -> cartService.addItemToCart(user.getProviderId(), requestDto));
    }

    @Test
    @DisplayName("유저는 카트에 있는 모든 상품들을 조회할 수 있다.")
    @Transactional
    void givenProviderId_whenGetCartItems_thenShouldGetSuccess() {
        // Given
        AddItemToCartRequestDto requestDto = AddItemToCartRequestDto.builder()
                                                                    .productId(product.getId())
                                                                    .quantity(10).build();

        AddItemToCartRequestDto requestDto2 = AddItemToCartRequestDto.builder()
                                                                    .productId(product2.getId())
                                                                    .quantity(20).build();

        cartService.addItemToCart(user.getProviderId(), requestDto);
        cartService.addItemToCart(user.getProviderId(), requestDto2);

        // When
        List<CartItemResponseDto> items = cartService.getCartItems(user.getProviderId());
        List<CartItemProjection> selectItems = cartItemRepository.findCartItemsByCartId(user.getCart().getId());

        // Then
        assertEquals(selectItems.get(0).getCartItemId(), items.get(0).getCartItemId());
        assertEquals(requestDto.getQuantity(), items.get(0).getProductQuantity());
        assertEquals(requestDto.getProductId(), items.get(0).getProductId());

        assertEquals(selectItems.get(1).getCartItemId(), items.get(1).getCartItemId());
        assertEquals(requestDto2.getQuantity(), items.get(1).getProductQuantity());
        assertEquals(requestDto2.getProductId(), items.get(1).getProductId());
    }
}
