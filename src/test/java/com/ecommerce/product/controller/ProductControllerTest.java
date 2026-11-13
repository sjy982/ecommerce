package com.ecommerce.product.controller;

import static com.ecommerce.config.TestConstants.TEST_PROVIDER_ID;
import static com.ecommerce.config.TestConstants.TEST_STORE_ROLE;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.ecommerce.product.dto.RegisterProductRequestDto;
import com.ecommerce.product.dto.RegisterProductResponseDto;
import com.ecommerce.product.service.ProductService;
import com.ecommerce.security.WithMockCustomUser;
import com.ecommerce.stock.SaleService;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProductService productService;

    @MockBean
    private SaleService saleService;

    @Test
    @DisplayName("새로운 상품을 등록하고 올바른 응답을 반환해야 한다.")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_STORE_ROLE)
    void givenRegisterProductDto_whenRegisterProduct_thenShouldReturnCratedResponse() throws Exception {
        // Given
        RegisterProductRequestDto requestDto = RegisterProductRequestDto.builder()
                                                                        .categoryId(1L)
                                                                        .stock(10)
                                                                        .price(100L)
                                                                        .name(TEST_PROVIDER_ID)
                                                                        .description("testDiscription")
                                                                        .build();

        String store = "test store";
        Long price = 100L;
        Integer stock = 10;
        String description = "test description";
        String category = "식품";
        RegisterProductResponseDto responseDto = RegisterProductResponseDto.builder()
                                                                           .store(store)
                                                                           .price(price)
                                                                           .stock(stock)
                                                                           .description(description)
                                                                           .category(category)
                                                                           .name(TEST_PROVIDER_ID)
                                                                           .build();
        String requestBody = objectMapper.writeValueAsString(requestDto);
        when(productService.registerProduct(TEST_PROVIDER_ID, requestDto)).thenReturn(responseDto);

        // When & Then
        mockMvc.perform(post("/api/product").
                header("Authorization", "Bearer temp-access-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.store").value(store))
                .andExpect(jsonPath("$.data.name").value(TEST_PROVIDER_ID))
                .andExpect(jsonPath("$.data.price").value(price))
                .andExpect(jsonPath("$.data.stock").value(stock))
                .andExpect(jsonPath("$.data.description").value(description))
                .andExpect(jsonPath("$.data.category").value(category));
    }

    @Test
    @DisplayName("스토어가 판매를 오픈하면 Redis에 적재한 수량을 응답한다")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_STORE_ROLE)
    void givenStore_whenOpenSale_thenReturnsLoadedStock() throws Exception {
        when(saleService.open(TEST_PROVIDER_ID, 1L)).thenReturn(96L);

        mockMvc.perform(post("/api/product/1/sale/open"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.productId").value(1))
               .andExpect(jsonPath("$.data.stock").value(96));
    }
}
