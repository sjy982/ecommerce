package com.ecommerce.payment.job.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ecommerce.payment.job.model.PaymentJob;
import com.ecommerce.payment.job.model.PaymentJobStatus;

public interface PaymentJobRepository extends JpaRepository<PaymentJob, Long> {
    @Query("""
    select pj.id
    from PaymentJob pj
    where pj.status = :status
    order by pj.id asc
    """)
    List<Long> findIdsByStatus(@Param("status") PaymentJobStatus status, Pageable pageable);

    /**
     * 조건부 UPDATE(CAS). 현재 상태가 from일 때만 to로 바꾼다.
     * UPDATE는 최신 커밋 값을 락을 걸고 읽으므로(current read), 동시에 호출해도 한 쪽만 1을 받는다.
     * @return 바뀐 행 수 (1 = 성공, 0 = 이미 다른 상태)
     */
    @Modifying
    @Query("""
    update PaymentJob pj
       set pj.status = :to
     where pj.id = :id
       and pj.status = :from
    """)
    int changeStatus(@Param("id") Long id,
                     @Param("from") PaymentJobStatus from,
                     @Param("to") PaymentJobStatus to);
}
