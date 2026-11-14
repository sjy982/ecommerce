package com.ecommerce.payment;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.notification.service.NotificationService;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.model.Orders;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.payment.job.model.PaymentJob;
import com.ecommerce.payment.job.model.PaymentJobStatus;
import com.ecommerce.payment.job.repository.PaymentJobRepository;
import com.ecommerce.payment.job.service.PaymentJobService;
import com.ecommerce.stock.RedisStockRepository;
import com.ecommerce.stock.StockRestoreResult;
import com.ecommerce.stock.exception.StockUnavailableException;
import com.ecommerce.store.service.StoreService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 결과를 우리 DB와 Redis에 반영한다. PG 호출은 하지 않는다(PaymentStarter가 한다).
 *
 * 모든 반영은 "주문 상태 조건부 UPDATE가 1행일 때만" 후속 작업을 한다.
 * 같은 결제가 두 번 처리돼도(재시도, 중복 실행) 매출 증가·알림·재고 복구가 두 번 일어나지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final NotificationService notificationService;
    private final StoreService storeService;
    private final PaymentJobService paymentJobService;
    private final PaymentJobRepository paymentJobRepository;
    private final RedisStockRepository redisStockRepository;

    /** PG에 보내는 멱등키. 결제는 주문 하나에 하나이므로 주문 ID로 만든다(재시도해도 항상 같은 값). */
    public static String paymentKey(Long orderId) {
        return "order-" + orderId;
    }

    public static long amountOf(Orders order) {
        return order.getProduct().getPrice() * order.getQuantity();
    }

    /** 결제 승인 반영: 주문 PAID, 매출 증가, 알림, job 삭제 (한 트랜잭션) */
    @Transactional
    public void completePayment(Long paymentJobId) {
        PaymentJob job = paymentJobService.findById(paymentJobId);
        if (orderRepository.changeStatus(job.getOrderId(), OrderStatus.PENDING_PAYMENT, OrderStatus.PAID) == 1) {
            Orders order = orderService.findById(job.getOrderId());
            storeService.increaseTotalSales(order.getStore().getId(), amountOf(order));   // 원자적 UPDATE
            notificationService.createNotification(order);
        } else {
            log.warn("결제 승인을 반영하려 했지만 주문 {}은(는) 이미 결제 대기 상태가 아닙니다. job만 정리합니다.", job.getOrderId());
        }
        paymentJobRepository.delete(job);
    }

    /**
     * 결제 최종 실패 반영: 주문 PAYMENT_FAILED, 알림, job을 RESTOCK_PENDING으로 (한 트랜잭션).
     * 이 커밋 순간 DB 기준으로 재고가 돌아온다(판매 오픈 계산이 실패 주문을 제외하므로). Redis는 커밋 후 restock에서 맞춘다.
     * @return Redis 재고 복구를 해야 하면 true
     */
    @Transactional
    public boolean failPayment(Long paymentJobId) {
        PaymentJob job = paymentJobService.findById(paymentJobId);
        if (orderRepository.changeStatus(job.getOrderId(), OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_FAILED) == 1) {
            notificationService.createNotification(orderService.findById(job.getOrderId()));
            job.markRestockPending();
            return true;
        }
        if (job.getStatus() == PaymentJobStatus.RESTOCK_PENDING) {
            return true;   // 이미 실패 처리됐고 복구만 남은 상태: 복구는 멱등이라 다시 시도해도 된다
        }
        log.warn("결제 실패를 반영하려 했지만 주문 {}은(는) 이미 결제 대기 상태가 아닙니다. job만 정리합니다.", job.getOrderId());
        paymentJobRepository.delete(job);
        return false;
    }

    /**
     * Redis 재고 복구 (늘릴 때는 DB 먼저, Redis는 그다음).
     * 복구는 주문 ID 장부로 멱등하므로, 실패하면 job을 RESTOCK_PENDING으로 남겨 스위퍼가 안전하게 재시도한다.
     */
    public void restock(Long paymentJobId) {
        Optional<PaymentJob> job = paymentJobRepository.findById(paymentJobId);
        if (job.isEmpty() || job.get().getStatus() != PaymentJobStatus.RESTOCK_PENDING) {
            return;
        }
        Orders order = orderService.findById(job.get().getOrderId());
        try {
            StockRestoreResult result =
                    redisStockRepository.restore(order.getProduct().getId(), order.getId(), order.getQuantity());
            paymentJobService.deleteIfStatus(paymentJobId, PaymentJobStatus.RESTOCK_PENDING);
            log.info("결제 실패 주문 {} 재고 복구: {}", order.getId(), result);
        } catch (StockUnavailableException e) {
            log.warn("결제 실패 주문 {}의 Redis 재고 복구 실패. 스위퍼가 다시 시도합니다.", order.getId(), e);
        }
    }
}
