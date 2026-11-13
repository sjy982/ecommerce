package com.ecommerce.order.controller;

import static com.ecommerce.config.TestConstants.TEST_PROVIDER_ID;
import static com.ecommerce.config.TestConstants.TEST_STORE_ID;
import static com.ecommerce.config.TestConstants.TEST_STORE_ROLE;
import static com.ecommerce.config.TestConstants.TEST_USER_ROLE;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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

import com.ecommerce.order.DTO.OrderProductDto;
import com.ecommerce.order.DTO.OrderProductRequestDto;
import com.ecommerce.order.DTO.OrderProductResponseDto;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.security.WithMockCustomUser;
import com.ecommerce.stock.exception.NotOnSaleException;
import com.ecommerce.stock.exception.SoldOutException;
import com.ecommerce.stock.exception.StockUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
class OrderControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    @Test
    @DisplayName("유효한 사용자가 특정 상품을 주문했을 때 올바른 응답을 반환해야 한다.")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_USER_ROLE)
    void givenValidUser_whenOrderProduct_thenShouldReturnCreatedResponse() throws Exception {
        // Given
        OrderProductRequestDto requestDto = OrderProductRequestDto.builder()
                .productId(1L)
                .phoneNumber("010-1234-1234")
                .deliveryAddress("test Address")
                .quantity(10).build();

        String requestBody = objectMapper.writeValueAsString(requestDto);

        OrderProductResponseDto responseDto = OrderProductResponseDto.builder()
                                                                     .orderProduct(OrderProductDto.builder()
                                                                                                  .name("test Name")
                                                                                                  .quantity(10).price(100L).
                                                                                                  build())
                                                                     .deliveryAddress("test Address")
                                                                     .phoneNumber("010-1234-1234").build();

        when(orderService.orderProductAndPaymentRequest(TEST_PROVIDER_ID, requestDto)).thenReturn(responseDto);

        // When && Then
        mockMvc.perform(post("/api/orders/payment")
                                .header("Authorization", "Bearer valid-access-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderProduct.name").value("test Name"))
                .andExpect(jsonPath("$.data.phoneNumber").value("010-1234-1234"))
                .andExpect(jsonPath("$.data.orderProduct.price").value(100))
                .andExpect(jsonPath("$.data.deliveryAddress").value("test Address"))
                .andExpect(jsonPath("$.data.orderProduct.quantity").value(10));
    }

    private String orderBody() throws Exception {
        return objectMapper.writeValueAsString(OrderProductRequestDto.builder()
                .productId(1L).phoneNumber("010-1234-1234").deliveryAddress("test Address").quantity(1).build());
    }

    @Test
    @DisplayName("품절이면 409 Conflict를 응답한다")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_USER_ROLE)
    void givenSoldOut_whenOrder_thenConflict() throws Exception {
        when(orderService.orderProductAndPaymentRequest(org.mockito.ArgumentMatchers.eq(TEST_PROVIDER_ID), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new SoldOutException());

        mockMvc.perform(post("/api/orders/payment").contentType(MediaType.APPLICATION_JSON).content(orderBody()))
               .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("판매 오픈 전 상품이면 409 Conflict를 응답한다")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_USER_ROLE)
    void givenNotOnSale_whenOrder_thenConflict() throws Exception {
        when(orderService.orderProductAndPaymentRequest(org.mockito.ArgumentMatchers.eq(TEST_PROVIDER_ID), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new NotOnSaleException());

        mockMvc.perform(post("/api/orders/payment").contentType(MediaType.APPLICATION_JSON).content(orderBody()))
               .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Redis 장애로 재고를 판정할 수 없으면 503 Service Unavailable을 응답한다")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_USER_ROLE)
    void givenStockUnavailable_whenOrder_thenServiceUnavailable() throws Exception {
        when(orderService.orderProductAndPaymentRequest(org.mockito.ArgumentMatchers.eq(TEST_PROVIDER_ID), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new StockUnavailableException(new RuntimeException("down")));

        mockMvc.perform(post("/api/orders/payment").contentType(MediaType.APPLICATION_JSON).content(orderBody()))
               .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("유효한 사용자가 주문한 내용을 조회했을 때 올바른 응답을 반환해야 한다.")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_USER_ROLE)
    void givenValidUser_whenGetUserOrder_thenShouldReturnOKResponse() throws Exception {
        // Given
        OrderProductResponseDto responseDto = OrderProductResponseDto.builder()
                                                                     .orderProduct(OrderProductDto.builder()
                                                                                                  .name("test Name")
                                                                                                  .quantity(10).price(100L).
                                                                                                  build())
                                                                     .deliveryAddress("test Address")
                                                                     .phoneNumber("010-1234-1234").build();

        when(orderService.getUserOrderProductResponseDto(1L, TEST_PROVIDER_ID)).thenReturn(responseDto);

        // When && Then
        mockMvc.perform(get("/api/orders/user/1")
                                .header("Authorization", "Bearer valid-access-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.orderProduct.name").value("test Name"))
               .andExpect(jsonPath("$.data.phoneNumber").value("010-1234-1234"))
               .andExpect(jsonPath("$.data.orderProduct.price").value(100))
               .andExpect(jsonPath("$.data.deliveryAddress").value("test Address"))
               .andExpect(jsonPath("$.data.orderProduct.quantity").value(10));
    }

    @Test
    @DisplayName("유효한 사용자가 모든 주문한 내용을 조회했을 때 올바른 응답을 반환해야 한다.")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_USER_ROLE)
    void givenValidUser_whenGetAllUserOrders_thenShouldReturnOKResponse() throws Exception {
        // Given
        OrderProductResponseDto responseDto1 = OrderProductResponseDto.builder()
                                                                     .orderProduct(OrderProductDto.builder()
                                                                                                  .name("test Name")
                                                                                                  .quantity(10).price(100L).
                                                                                                  build())
                                                                     .deliveryAddress("test Address")
                                                                     .phoneNumber("010-1234-1234").build();

        OrderProductResponseDto responseDto2 = OrderProductResponseDto.builder()
                                                                     .orderProduct(OrderProductDto.builder()
                                                                                                  .name("test Name2")
                                                                                                  .quantity(20).price(200L).
                                                                                                  build())
                                                                     .deliveryAddress("test Address")
                                                                     .phoneNumber("010-1234-1234").build();


        when(orderService.getUserAllOrderProductsResponseDto(TEST_PROVIDER_ID)).thenReturn(List.of(responseDto1, responseDto2));

        // When && Then
        mockMvc.perform(get("/api/orders/user")
                                .header("Authorization", "Bearer valid-access-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data[0].orderProduct.name").value(responseDto1.getOrderProduct().getName()))
               .andExpect(jsonPath("$.data[0].phoneNumber").value(responseDto1.getPhoneNumber()))
               .andExpect(jsonPath("$.data[0].orderProduct.price").value(responseDto1.getOrderProduct().getPrice()))
               .andExpect(jsonPath("$.data[0].deliveryAddress").value(responseDto1.getDeliveryAddress()))
               .andExpect(jsonPath("$.data[0].orderProduct.quantity").value(responseDto1.getOrderProduct().getQuantity()))
               .andExpect(jsonPath("$.data[1].orderProduct.name").value(responseDto2.getOrderProduct().getName()))
               .andExpect(jsonPath("$.data[1].phoneNumber").value(responseDto2.getPhoneNumber()))
               .andExpect(jsonPath("$.data[1].orderProduct.price").value(responseDto2.getOrderProduct().getPrice()))
               .andExpect(jsonPath("$.data[1].deliveryAddress").value(responseDto2.getDeliveryAddress()))
               .andExpect(jsonPath("$.data[1].orderProduct.quantity").value(responseDto2.getOrderProduct().getQuantity()));
    }

    @Test
    @DisplayName("유효한 상점은 주문이 들어온 내용을 조회했을 때 올바른 응답을 반환해야 한다.")
    @WithMockCustomUser(username = TEST_STORE_ID, role = TEST_STORE_ROLE)
    void givenValidStore_whenGetStoreOrder_thenShouldReturnOKResponse() throws Exception {
        // Given
        OrderProductResponseDto responseDto = OrderProductResponseDto.builder()
                                                                     .orderProduct(OrderProductDto.builder()
                                                                                                  .name("test Name")
                                                                                                  .quantity(10).price(100L).
                                                                                                  build())
                                                                     .deliveryAddress("test Address")
                                                                     .phoneNumber("010-1234-1234").build();

        when(orderService.getStoreOrderProductResponseDto(1L, Long.parseLong(TEST_STORE_ID))).thenReturn(responseDto);

        // When && Then
        mockMvc.perform(get("/api/orders/store/1")
                                .header("Authorization", "Bearer valid-access-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.orderProduct.name").value("test Name"))
               .andExpect(jsonPath("$.data.phoneNumber").value("010-1234-1234"))
               .andExpect(jsonPath("$.data.orderProduct.price").value(100))
               .andExpect(jsonPath("$.data.deliveryAddress").value("test Address"))
               .andExpect(jsonPath("$.data.orderProduct.quantity").value(10));
    }

    @Test
    @DisplayName("유효한 상점은 주문이 들어온 모든 내용을 조회했을 때 올바른 응답을 반환해야 한다.")
    @WithMockCustomUser(username = TEST_STORE_ID, role = TEST_STORE_ROLE)
    void givenValidStore_whenGetAllStoreOrders_thenShouldReturnOKResponse() throws Exception {
        // Given
        OrderProductResponseDto responseDto1 = OrderProductResponseDto.builder()
                                                                      .orderProduct(OrderProductDto.builder()
                                                                                                   .name("test Name")
                                                                                                   .quantity(10).price(100L).
                                                                                                   build())
                                                                      .deliveryAddress("test Address")
                                                                      .phoneNumber("010-1234-1234").build();

        OrderProductResponseDto responseDto2 = OrderProductResponseDto.builder()
                                                                      .orderProduct(OrderProductDto.builder()
                                                                                                   .name("test Name2")
                                                                                                   .quantity(20).price(200L).
                                                                                                   build())
                                                                      .deliveryAddress("test Address")
                                                                      .phoneNumber("010-1234-1234").build();


        when(orderService.getStoreAllOrderProductsResponseDto(Long.parseLong(TEST_STORE_ID))).thenReturn(List.of(responseDto1, responseDto2));

        // When && Then
        mockMvc.perform(get("/api/orders/store")
                                .header("Authorization", "Bearer valid-access-token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data[0].orderProduct.name").value(responseDto1.getOrderProduct().getName()))
               .andExpect(jsonPath("$.data[0].phoneNumber").value(responseDto1.getPhoneNumber()))
               .andExpect(jsonPath("$.data[0].orderProduct.price").value(responseDto1.getOrderProduct().getPrice()))
               .andExpect(jsonPath("$.data[0].deliveryAddress").value(responseDto1.getDeliveryAddress()))
               .andExpect(jsonPath("$.data[0].orderProduct.quantity").value(responseDto1.getOrderProduct().getQuantity()))
               .andExpect(jsonPath("$.data[1].orderProduct.name").value(responseDto2.getOrderProduct().getName()))
               .andExpect(jsonPath("$.data[1].phoneNumber").value(responseDto2.getPhoneNumber()))
               .andExpect(jsonPath("$.data[1].orderProduct.price").value(responseDto2.getOrderProduct().getPrice()))
               .andExpect(jsonPath("$.data[1].deliveryAddress").value(responseDto2.getDeliveryAddress()))
               .andExpect(jsonPath("$.data[1].orderProduct.quantity").value(responseDto2.getOrderProduct().getQuantity()));
    }
}
