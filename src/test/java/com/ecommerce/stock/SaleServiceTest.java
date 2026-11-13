package com.ecommerce.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.cart.model.Cart;
import com.ecommerce.category.model.Category;
import com.ecommerce.category.repository.CategoryRepository;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.model.Orders;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.store.model.Store;
import com.ecommerce.store.repository.StoreRepository;
import com.ecommerce.user.model.Users;
import com.ecommerce.user.repository.UserRepository;

/**
 * 판매 오픈: Redis 재고는 항상 DB 기준으로 계산해서 넣는다.
 * 남은 재고 = product.stock(판매 총수량) − 결제 실패가 아닌 주문 수량 합
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SaleServiceTest {
    @Autowired private SaleService saleService;
    @Autowired private StoreRepository storeRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;

    @MockBean private RedisStockRepository redisStockRepository;

    private Store store;
    private Product product;
    private Users user;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(Store.builder()
                .name("sale-store").password("pw").phoneNumber("010-1234-1234").totalSales(0L).build());
        Category category = new Category();
        category.setName("sale-category");
        categoryRepository.save(category);
        product = productRepository.save(Product.builder()
                .name("limited").price(100L).stock(100).store(store).category(category).build());
        user = userRepository.save(Users.builder()
                .subject("s").providerId("sale-user").provider("test").address("a")
                .email("sale-user@test.com").phone("010-1234-1234").name("n").cart(new Cart()).build());
    }

    private void order(int quantity, OrderStatus status) {
        orderRepository.save(Orders.builder()
                .user(user).store(store).product(product).quantity(quantity)
                .deliveryAddress("a").phoneNumber("010-1234-1234").status(status).build());
    }

    @Test
    @DisplayName("주문이 없으면 판매 총수량 그대로 Redis에 적재한다")
    void givenNoOrders_whenOpen_thenLoadTotalStock() {
        long opened = saleService.open(store.getName(), product.getId());

        assertThat(opened).isEqualTo(100);
        verify(redisStockRepository).open(product.getId(), 100);
    }

    @Test
    @DisplayName("이미 있는 유효 주문(결제 대기·결제 완료)만큼 빼고, 결제 실패 주문은 빼지 않는다")
    void givenExistingOrders_whenOpen_thenSubtractActiveOrdersOnly() {
        order(1, OrderStatus.PENDING_PAYMENT);
        order(1, OrderStatus.PENDING_PAYMENT);
        order(2, OrderStatus.PAID);
        order(5, OrderStatus.PAYMENT_FAILED);

        long opened = saleService.open(store.getName(), product.getId());

        assertThat(opened).isEqualTo(96);
        verify(redisStockRepository).open(product.getId(), 96);
    }

    @Test
    @DisplayName("다른 스토어의 상품은 판매 오픈할 수 없다")
    void givenOtherStore_whenOpen_thenNotFound() {
        assertThatThrownBy(() -> saleService.open("other-store", product.getId()))
                .isInstanceOf(UsernameNotFoundException.class);
        verify(redisStockRepository, never()).open(anyLong(), anyLong());
    }
}
