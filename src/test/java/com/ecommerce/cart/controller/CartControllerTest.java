package com.ecommerce.cart.controller;

import static com.ecommerce.config.TestConstants.TEST_PROVIDER_ID;
import static com.ecommerce.config.TestConstants.TEST_USER_ROLE;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.ecommerce.cart.DTO.AddItemToCartRequestDto;
import com.ecommerce.cart.DTO.AddItemToCartResponseDto;
import com.ecommerce.cart.DTO.CartItemResponseDto;
import com.ecommerce.cart.service.CartService;
import com.ecommerce.order.DTO.OrderProductDto;
import com.ecommerce.security.WithMockCustomUser;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
class CartControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CartService cartService;

    @Test
    @DisplayName("유효한 사용자가 재고량이 충분한 특정 상품을 카트에 담았을 때 올바른 응답을 반환해야 한다.")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_USER_ROLE)
    void givenValidUser_whenAddToItemCart_thenShouldReturnCreateResponse() throws Exception {
        // Given
        AddItemToCartRequestDto requestDto = AddItemToCartRequestDto.builder()
                                                                    .productId(1L)
                                                                    .quantity(10).build();

        String requestBody = objectMapper.writeValueAsString(requestDto);

        AddItemToCartResponseDto responseDto = AddItemToCartResponseDto.builder()
                .productName("testName")
                .quantity(10).build();

        when(cartService.addItemToCart(TEST_PROVIDER_ID, requestDto)).thenReturn(responseDto);

        // When & Then
        mockMvc.perform(post("/api/cart/item")
                                .header("Authorization", "Bearer valid-access-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productName").value("testName"))
                .andExpect(jsonPath("$.data.quantity").value(10));
    }

    @Test
    @DisplayName("유효한 사용자는 cart의 모든 item을 조회할 수 있어야 한다.")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_USER_ROLE)
    void givenValidUser_whenGetCartItem_thenShouldReturnCreateResponse() throws Exception {
        // Given
        CartItemResponseDto responseDto1 = CartItemResponseDto.builder()
                                                              .cartItemId(1L)
                                                              .productId(1L)
                                                              .productName("testName1")
                                                              .productPrice(100L)
                                                              .productQuantity(10).build();

        CartItemResponseDto responseDto2 = CartItemResponseDto.builder()
                                                              .cartItemId(2L)
                                                              .productId(2L)
                                                              .productName("testName2")
                                                              .productPrice(100L)
                                                              .productQuantity(10).build();

        List<CartItemResponseDto> items = List.of(responseDto1, responseDto2);

        when(cartService.getCartItems(TEST_PROVIDER_ID)).thenReturn(items);

        // When && Then
        mockMvc.perform(get("/api/cart/items")
                                .header("Authorization", "Bearer valid-access-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data[0].productId").value(responseDto1.getProductId()))
               .andExpect(jsonPath("$.data[0].productQuantity").value(responseDto1.getProductQuantity()))
               .andExpect(jsonPath("$.data[0].cartItemId").value(responseDto1.getCartItemId()))
               .andExpect(jsonPath("$.data[1].productId").value(responseDto2.getProductId()))
               .andExpect(jsonPath("$.data[1].productQuantity").value(responseDto2.getProductQuantity()))
               .andExpect(jsonPath("$.data[1].cartItemId").value(responseDto2.getCartItemId()));
    }
}
