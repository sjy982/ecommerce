package com.ecommerce.common;

import static com.ecommerce.config.TestConstants.TEST_PROVIDER_ID;
import static com.ecommerce.config.TestConstants.TEST_TEMP_ROLE;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.ecommerce.order.DTO.OrderProductRequestDto;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.security.WithMockCustomUser;
import com.ecommerce.store.DTO.LoginStoreRequestDto;
import com.ecommerce.store.DTO.RegisterStoreRequestDto;
import com.ecommerce.store.Exception.InvalidPasswordException;
import com.ecommerce.store.service.StoreService;
import com.ecommerce.user.DTO.RegisterUserRequestDto;
import com.ecommerce.user.Exception.RefreshTokenException;
import com.ecommerce.user.Exception.SessionExpiredException;
import com.ecommerce.user.model.UserRole;
import com.ecommerce.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    @MockBean
    private StoreService storeService;

    @MockBean
    private OrderService orderService;

    @Test
    @DisplayName("유효하지 않은 DTO 요청으로 BAD_REQUEST 응답을 반환해야 한다")
    void givenInvalidDtoRequest_whenHandleValidationExceptions_thenReturnBadRequest() throws Exception {
        // Given: 잘못된 DTO 요청
        String invalidRequestBody = "{}";

        // When & Then
        mockMvc.perform(post("/api/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidRequestBody))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message").value("Validation failed. Please correct the highlighted fields."));
    }

    @Test
    @DisplayName("SessionExpiredException이 발생하면 UNAUTHORIZED 응답을 반환해야 한다")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_TEMP_ROLE)
    void givenSessionExpiredException_whenHandleException_thenReturnUnauthorized() throws Exception {
        // Given: Service에서 예외 발생 설정
        RegisterUserRequestDto requestDto = new RegisterUserRequestDto();
        requestDto.setPhone("010-1234-4567");
        requestDto.setAddress("Test Address");
        when(userService.registerUser(TEST_PROVIDER_ID, requestDto)).thenThrow(new SessionExpiredException("Session expired"));

        String requestBody = objectMapper.writeValueAsString(requestDto);

        // When & Then: 요청 본문과 함께 테스트 실행
        mockMvc.perform(post("/api/users")
                                .header("Authorization", "Bearer temp-access-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
               .andExpect(status().isUnauthorized())
               .andExpect(jsonPath("$.message").value("session has expired."));
    }

    @Test
    @DisplayName("RefreshTokenException이 발생하면 UNAUTHORIZED 응답을 반환해야 한다")
    void givenRefreshTokenException_whenHandleException_thenReturnUnauthorized() throws Exception {
        // Given
        String refreshToken = "valid-refresh-token";
        String sub = "test-providerId";

        when(userService.refreshTokens(sub, UserRole.USER.name(), refreshToken)).thenThrow(new RefreshTokenException("Refresh Token mismatch or expired"));

        // When & Then
        mockMvc.perform(post("/api/users/refresh")
                                .header("Refresh-Token", refreshToken)
                                .requestAttr("sub", sub)
                                .requestAttr("role", UserRole.USER.name()))
               .andExpect(status().isUnauthorized())
               .andExpect(jsonPath("$.message").value("Refresh Token mismatch or expired"));
    }

    @Test
    @DisplayName("JsonProcessingException이 발생하면 INTERNAL_SERVER_ERROR 응답을 반환해야 한다")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_TEMP_ROLE)
    void givenJsonProcessingException_whenHandleException_thenReturnInternalServerError() throws Exception {
        // Given: Service에서 예외 발생 설정
        RegisterUserRequestDto requestDto = new RegisterUserRequestDto();
        requestDto.setPhone("010-1234-4567");
        requestDto.setAddress("Test Address");

        when(userService.registerUser(TEST_PROVIDER_ID, requestDto)).thenThrow(new RuntimeException("Error deserializing JSON to ..."));

        String requestBody = objectMapper.writeValueAsString(requestDto);

        // When & Then
        mockMvc.perform(post("/api/users")
                                .header("Authorization", "Bearer temp-access-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
               .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("HttpMessageNotReableException이 발생하면 Bad Request를 응답해야 한다.")
    void givenHttpMessageNotReableException_whenHandleException_thenReturnBadRequest() throws Exception {
        mockMvc.perform(post("/api/store"))
               .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DuplicateKeyException 발생하면 CONFLICT를 응답해야 한다.")
    void givenDuplicateKeyException_whenHandleException_thenReturnConflict() throws Exception {
        // Given
        RegisterStoreRequestDto requestDto = new RegisterStoreRequestDto();
        requestDto.setName("test name");
        requestDto.setPassword("test pw");
        requestDto.setPhoneNumber("010-1234-1234");

        String requestBody = objectMapper.writeValueAsString(requestDto);

        when(storeService.registerStore(requestDto)).thenThrow(DuplicateKeyException.class);

        // When && Then
        mockMvc.perform(post("/api/store")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
               .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("UsernameNotFoundException 발생하면 Not found를 응답해야 한다.")
    void givenUsernameNotFoundException_whenHandleException_thenReturnNotFound() throws Exception {
        LoginStoreRequestDto requestDto = new LoginStoreRequestDto();
        requestDto.setName("test name");
        requestDto.setPassword("test pw");

        String requestBody = objectMapper.writeValueAsString(requestDto);

        when(storeService.loginStore(requestDto)).thenThrow(UsernameNotFoundException.class);

        mockMvc.perform(post("/api/store/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
               .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("InvalidPasswordException이 발생하면 Unauthorized를 응답해야 한다.")
    void givenInvalidPasswordException_whenHandleException_thenReturnUnauthorized() throws Exception {
        LoginStoreRequestDto requestDto = new LoginStoreRequestDto();
        requestDto.setName("test name");
        requestDto.setPassword("failed pw");

        String requestBody = objectMapper.writeValueAsString(requestDto);

        when(storeService.loginStore(requestDto)).thenThrow(InvalidPasswordException.class);

        mockMvc.perform(post("/api/store/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CannotAcquireLockException 발생하면 InternalServerError를 응답해야 한다.")
    @WithMockCustomUser(username = TEST_PROVIDER_ID, role = TEST_TEMP_ROLE)
    void givenCannotAcquireLockException_whenHandleRuntimeExceptionException_thenReturnInternalServerError() throws Exception {
        // CannotAcquireLockException 데드락이 발생하면 Spring에서 이 예외로 래핑되어 던져짐
        OrderProductRequestDto requestDto = OrderProductRequestDto
                .builder()
                .productId(1L)
                .quantity(10)
                .deliveryAddress("testAddress")
                .phoneNumber("010-1234-1234").build();

        String requestBody = objectMapper.writeValueAsString(requestDto);
        when(orderService.orderProductAndPaymentRequest(TEST_PROVIDER_ID, requestDto))
                .thenThrow(CannotAcquireLockException.class);

        mockMvc.perform(post("/api/orders/payment")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
               .andExpect(status().isInternalServerError());
    }
}
