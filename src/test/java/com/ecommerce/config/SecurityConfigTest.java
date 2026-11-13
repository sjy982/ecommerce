package com.ecommerce.config;

import static com.ecommerce.config.TestConstants.TEST_PROVIDER_ID;
import static com.ecommerce.config.TestConstants.TEST_STORE_ID;
import static com.ecommerce.config.TestConstants.TEST_STORE_ROLE;
import static com.ecommerce.config.TestConstants.TEST_USER_ROLE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.ecommerce.auth.exception.TokenInvalidException;
import com.ecommerce.auth.jwt.JwtProvider;
import com.ecommerce.cart.DTO.AddItemToCartRequestDto;
import com.ecommerce.cart.controller.CartController;
import com.ecommerce.notification.controller.NotificationController;
import com.ecommerce.order.DTO.OrderProductRequestDto;
import com.ecommerce.order.controller.OrderController;
import com.ecommerce.product.controller.ProductController;
import com.ecommerce.product.dto.RegisterProductRequestDto;
import com.ecommerce.store.DTO.RegisterStoreRequestDto;
import com.ecommerce.store.controller.StoreController;
import com.ecommerce.user.DTO.RegisterUserRequestDto;
import com.ecommerce.user.controller.UserController;
import com.ecommerce.user.model.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserController userController;

    @MockBean
    private JwtProvider jwtProvider;

    @MockBean
    private StoreController storeController;

    @MockBean
    private ProductController productController;

    @MockBean
    private OrderController orderController;

    @MockBean
    private CartController cartController;

    @MockBean
    private NotificationController notificationController;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("올바르지 않은 AccessToken이 주어졌을 때 401 Unauthorized를 반환한다.")
    void givenInvalidAccessToken_whenAccessingUsersEndpoint_thenReturnUnauthorized() throws Exception {
        // Given
        String invalidAccessToken = "invalid-access-token";
        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(invalidAccessToken);
        when(jwtProvider.validateAccessToken(invalidAccessToken)).thenThrow(new TokenInvalidException("Token is invalid"));

        // When & Then
        mockMvc.perform(post("/api/users/users")
                                .header("Authorization", "Bearer " + invalidAccessToken))
               .andExpect(status().isUnauthorized());
    }
    @Test
    @DisplayName("OAuth2 경로 접근 시 인증이 필요하다")
    void givenUnauthenticatedUser_whenAccessingOAuth2Endpoint_thenRedirectToLogin() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/auth/login"))
               .andExpect(status().isFound());
    }

    @Test
    @DisplayName("Refresh Token 경로는 인증 없이 접근 가능하다. 물론 올바른 Refresh Token이 필요하며, 이를 검증한 필터가 올바르게 동작해야 한다.")
    void givenValidRefreshToken_whenAccessingRefreshTokenEndpoint_thenAllowAccess() throws Exception {
        // Given
        String refreshToken = "valid-refresh-token";
        when(jwtProvider.resolveRefreshToken(any(HttpServletRequest.class))).thenReturn(refreshToken);
        when(jwtProvider.validateRefreshToken(refreshToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromRefreshToken(refreshToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromRefreshToken(refreshToken)).thenReturn(UserRole.USER.name());
        when(userController.refreshAccessToken(refreshToken, UserRole.USER.name(), TEST_PROVIDER_ID))
                .thenReturn(ResponseEntity.ok().build());

        // When & Then
        mockMvc.perform(post("/api/users/refresh")
                                .header("Refresh-Token", refreshToken))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("public 경로는 인증 없이 접근 가능하다.")
    void given_whenAccessingPublicPath_thenAllowAccess() throws Exception {
       //Given
        String requestBody = "{\"name\": \"testName\", \"password\": \"test pw\", \"phoneNumber\": \"010-1234-5678\"}";
        when(storeController.registerStore(any(RegisterStoreRequestDto.class)))
                .thenReturn(ResponseEntity.ok().build());

        // When & Then
        mockMvc.perform(post("/api/store")
                                .contentType("application/json")
                                .content(requestBody))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("올바르지 않은 Refresh Token이 주어졌을 때 401 Unauthorized가 반환된다.")
    void givenInvalidRefreshToken_whenAccessingRefreshTokenEndpoint_thenReturnUnauthorized() throws Exception {
        // Given
        String invalidRefreshToken = "invalid-refresh-token";
        when(jwtProvider.resolveRefreshToken(any(HttpServletRequest.class))).thenReturn(invalidRefreshToken);
        when(jwtProvider.validateRefreshToken(invalidRefreshToken)).thenThrow(new TokenInvalidException("Token is invalid"));

        // When & Then
        mockMvc.perform(post("/api/users/refresh")
                                .header("Refresh-Token", invalidRefreshToken))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("TEMP 권한이 없는 사용자는 /users 경로에 접근할 수 없다")
    void givenUserWithoutTempRole_whenAccessingUsersEndpoint_thenReturnForbidden() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("USER");

        // When & Then
        mockMvc.perform(post("/api/users")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TEMP 권한이 있는 경우 /users에 접근할 수 있다")
    void givenUserWithTempRole_whenAccessingUsersEndpoint_thenAllowAccess() throws Exception {
        // Given
        String accessToken = "valid-access-token";
        String requestBody = "{\"phone\": \"010-1111-1111\", \"address\": \"test address\"}";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("TEMP");

        when(userController.registerUser(any(RegisterUserRequestDto.class)))
                .thenReturn(ResponseEntity.ok().build());

        // When & Then
        mockMvc.perform(post("/api/users")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType("application/json")
                                .content(requestBody))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("STORE 권한이 없는 경우(ex USER 권한) /api/product에 접근할 수 없다.")
    void givenUserRole_whenAccessingStoreEndpoint_thenInaccessible() throws Exception {
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("USER");

        // When & Then
        mockMvc.perform(post("/api/product")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("STORE 권한이 있는 경우(ex USER 권한) /api/product에 접근할 수 있다.")
    void givenStoreRole_whenAccessingStoreEndpoint_thenAllowAccess() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("STORE");

        when(productController.registerProduct(any(RegisterProductRequestDto.class)))
                .thenReturn(ResponseEntity.ok().build());

        RegisterProductRequestDto requestDto = RegisterProductRequestDto.builder()
                                                                        .categoryId(1L)
                                                                        .stock(10)
                                                                        .price(100L)
                                                                        .name("test")
                                                                        .description("test")
                                                                        .build();
        String requestBody = objectMapper.writeValueAsString(requestDto);
        // When & Then
        mockMvc.perform(post("/api/product")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("STORE 권한이 있으면 post /api/product/{id}/sale/open(판매 오픈)에 접근할 수 있다.")
    void givenStoreRole_whenAccessingSaleOpenEndpoint_thenAllowAccess() throws Exception {
        String accessToken = "valid-access-token";
        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("STORE");
        when(productController.openSale(1L)).thenReturn(ResponseEntity.ok().build());

        mockMvc.perform(post("/api/product/1/sale/open")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("STORE 권한이 없으면(ex USER 권한) post /api/product/{id}/sale/open에 접근할 수 없다.")
    void givenUserRole_whenAccessingSaleOpenEndpoint_thenInaccessible() throws Exception {
        String accessToken = "valid-access-token";
        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("USER");

        mockMvc.perform(post("/api/product/1/sale/open")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("USER 권한이 있는 경우(ex USER 권한) post /api/orders/payment 접근할 수 있다.")
    void givenUserRole_whenAccessingOrderEndpoint_thenAllowAccess() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("USER");

        when(orderController.orderProductAndPaymentRequest(any(OrderProductRequestDto.class)))
                .thenReturn(ResponseEntity.ok().build());

         OrderProductRequestDto requestDto = OrderProductRequestDto.builder()
                                                                   .productId(1L)
                                                                   .phoneNumber("010-1234-1234")
                                                                   .deliveryAddress("test Address")
                                                                   .quantity(10).build();
        String requestBody = objectMapper.writeValueAsString(requestDto);
        // When & Then
        mockMvc.perform(post("/api/orders/payment")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER 권한이 없는 경우(ex USER 권한) post /api/orders/payment 접근할 수 없다.")
    void givenNotUserRole_whenAccessingOrderEndpoint_thenInaccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("TEMP");

        // When & Then
        mockMvc.perform(post("/api/orders/payment")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("USER 권한이 있는 경우(ex USER 권한) post /api/cart/item 접근할 수 있다.")
    void givenUserRole_whenAccessingCartItemEndpoint_thenAllowAccess() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("USER");

        when(cartController.addItemToCart(any(AddItemToCartRequestDto.class)))
                .thenReturn(ResponseEntity.ok().build());

        AddItemToCartRequestDto requestDto = AddItemToCartRequestDto.builder()
                                                                    .productId(1L)
                                                                    .quantity(10).build();

        String requestBody = objectMapper.writeValueAsString(requestDto);
        // When & Then
        mockMvc.perform(post("/api/cart/item")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER 권한이 없는 경우(ex USER 권한) post /api/cart/item 접근할 수 없다.")
    void givenNotUserRole_whenAccessingCartItemEndpoint_thenInaccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("TEMP");

        // When & Then
        mockMvc.perform(post("/api/cart/item")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("USER 권한이 있는 경우(ex USER 권한) get /api/cart/items 접근할 수 있다.")
    void givenUserRole_whenAccessingCartItemsEndpoint_thenAllowAccess() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("USER");

        when(cartController.getCartItems())
                .thenReturn(ResponseEntity.ok().build());

        // When & Then
        mockMvc.perform(get("/api/cart/items")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER 권한이 없는 경우(ex USER 권한) get /api/cart/items 접근할 수 없다.")
    void givenNotUserRole_whenAccessingCartItemsEndpoint_thenInaccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("TEMP");

        // When & Then
        mockMvc.perform(get("/api/cart/items")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Store 권한이 있는 경우(ex USER 권한) get /api/notification/unread 에 접근할 수 있다.")
    void givenStoreRole_whenAccessingUnreadNotificationEndpoint_thenAllowAccess() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_STORE_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("STORE");

        when(notificationController.getUnreadNotifications())
                .thenReturn(ResponseEntity.ok().build());

        // When & Then
        mockMvc.perform(get("/api/notification/unread")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Store 권한이 없는 경우(ex USER 권한) get /api/notification/unread 에 접근할 수 없다.")
    void givenNotStoreRole_whenAccessingUnreadNotificationEndpoint_thenInaccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_STORE_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("USER");

        // When & Then
        mockMvc.perform(get("/api/notification/unread")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Store 권한이 있는 경우(ex USER 권한) get /api/notification 에 접근할 수 있다.")
    void givenStoreRole_whenAccessingAllNotificationEndpoint_thenAllowAccess() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_STORE_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("STORE");

        when(notificationController.getAllNotifications())
                .thenReturn(ResponseEntity.ok().build());

        // When & Then
        mockMvc.perform(get("/api/notification")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Store 권한이 없는 경우(ex USER 권한) get /api/notification 에 접근할 수 없다.")
    void givenNotStoreRole_whenAccessingAllNotificationEndpoint_thenInaccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_STORE_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("USER");

        // When & Then
        mockMvc.perform(get("/api/notification")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Store 권한이 있는 경우(ex USER 권한) post /api/notification/{notificationId} 에 접근할 수 있다.")
    void givenStoreRole_whenAccessingMarkNotificationAsReadEndpoint_thenAllowAccess() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_STORE_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("STORE");

        when(notificationController.markNotificationAsRead(1L))
                .thenReturn(ResponseEntity.ok().build());

        // When & Then
        mockMvc.perform(patch("/api/notification/1")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Store 권한이 없는 경우(ex USER 권한) post /api/notification/{notificationId} 에 접근할 수 없다.")
    void givenNotStoreRole_whenAccessingMarkNotificationAsReadEndpoint_thenInaccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_STORE_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn("USER");

        // When & Then
        mockMvc.perform(patch("/api/notification/1")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Store 권한이 있는 경우 get /api/orders/store/{orderId} 에 접근할 수 있다.")
    void givenStoreRole_whenAccessingGetStoreOrderEndpoint_thenAccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_STORE_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn(TEST_STORE_ROLE);

        // When & Then
        when(orderController.getStoreOrder(1L))
                .thenReturn(ResponseEntity.ok().build());

        mockMvc.perform(get("/api/orders/store/1")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Store 권한이 없는 경우 get /api/orders/store/{orderId} 에 접근할 수 없다.")
    void givenNotStoreRole_whenAccessingGetStoreOrderEndpoint_thenInaccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_STORE_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn(TEST_USER_ROLE);

        // When & Then

        mockMvc.perform(get("/api/orders/store/1")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Store 권한이 있는 경우 get /api/orders/store 에 접근할 수 있다.")
    void givenStoreRole_whenAccessingGetAllStoreOrdersEndpoint_thenAccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_STORE_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn(TEST_STORE_ROLE);

        // When & Then
        when(orderController.getAllStoreOrders())
                .thenReturn(ResponseEntity.ok().build());

        mockMvc.perform(get("/api/orders/store")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Store 권한이 없는 경우 get /api/orders/store 에 접근할 수 없다.")
    void givenNotStoreRole_whenAccessingGetAllStoreOrdersEndpoint_thenInaccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_STORE_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn(TEST_USER_ROLE);

        // When & Then

        mockMvc.perform(get("/api/orders/store")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("User 권한이 있는 경우 get /api/orders/user/{orderId} 에 접근할 수 있다.")
    void givenUserRole_whenAccessingGetUserOrderEndpoint_thenAccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn(TEST_USER_ROLE);

        // When & Then
        when(orderController.getUserOrder(1L))
                .thenReturn(ResponseEntity.ok().build());

        mockMvc.perform(get("/api/orders/user/1")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("User 권한이 없는 경우 get /api/orders/user/{orderId} 에 접근할 수 없다.")
    void givenNotUserRole_whenAccessingGetUserOrderEndpoint_thenInaccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn(TEST_STORE_ROLE);

        // When & Then

        mockMvc.perform(get("/api/orders/user/1")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("User 권한이 있는 경우 get /api/orders/user 에 접근할 수 있다.")
    void givenUserRole_whenAccessingGetAllUserOrdersEndpoint_thenAccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn(TEST_USER_ROLE);

        // When & Then
        when(orderController.getAllUserOrders())
                .thenReturn(ResponseEntity.ok().build());

        mockMvc.perform(get("/api/orders/user")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("User 권한이 없는 경우 get /api/orders/user 에 접근할 수 없다.")
    void givenNotUserRole_whenAccessingGetAllUserOrdersEndpoint_thenInaccessible() throws Exception {
        // Given
        String accessToken = "valid-access-token";

        when(jwtProvider.resolveAccessToken(any(HttpServletRequest.class))).thenReturn(accessToken);
        when(jwtProvider.validateAccessToken(accessToken)).thenReturn(true);
        when(jwtProvider.getSubjectFromAccessToken(accessToken)).thenReturn(TEST_PROVIDER_ID);
        when(jwtProvider.getRoleFromAccessToken(accessToken)).thenReturn(TEST_STORE_ROLE);

        // When & Then

        mockMvc.perform(get("/api/orders/user")
                                .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isForbidden());
    }
}
