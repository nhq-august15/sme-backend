package sme.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.Notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("SELECT n FROM Notification n WHERE (n.userId = :userId OR n.userId IS NULL) AND n.isRead = false ORDER BY n.createdAt DESC")
    List<Notification> findForUserAndGlobalUnread(@Param("userId") UUID userId);

    @Query("SELECT n FROM Notification n WHERE n.isRead = false ORDER BY n.createdAt DESC")
    List<Notification> findAllUnread();

    @Query("SELECT COUNT(n) FROM Notification n WHERE (n.userId = :userId OR n.userId IS NULL) AND n.isRead = false")
    long countForUserAndGlobalUnread(@Param("userId") UUID userId);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.isRead = false")
    long countAllUnread();

    @Query("SELECT n FROM Notification n WHERE n.userId = :userId OR n.userId IS NULL ORDER BY n.createdAt DESC")
    Page<Notification> findForUserAndGlobal(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT n FROM Notification n WHERE n.userId = :userId OR n.userId IS NULL ORDER BY n.createdAt DESC")
    List<Notification> findForUserAndGlobalList(@Param("userId") UUID userId);

    @Query("SELECT n FROM Notification n ORDER BY n.createdAt DESC")
    Page<Notification> findAllNotifications(Pageable pageable);

    @Query("SELECT n FROM Notification n WHERE n.type IN ('LOW_STOCK', 'OUT_OF_STOCK') ORDER BY n.createdAt DESC")
    List<Notification> findRecentStockNotifications(Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.isRead = true WHERE (n.userId = :userId OR n.userId IS NULL) AND n.isRead = false")
    void markAllUnreadAsReadForUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.isRead = false")
    void markAllUnreadAsReadForAdmin(); 

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM notifications " +
            "WHERE type IN ('LOW_STOCK', 'OUT_OF_STOCK') " +
            "AND id NOT IN (" +
            "    SELECT id FROM (" +
            "        SELECT DISTINCT ON (payload->>'productId', payload->>'warehouseId') id " +
            "        FROM notifications " +
            "        WHERE type IN ('LOW_STOCK', 'OUT_OF_STOCK') " +
            "        ORDER BY payload->>'productId', payload->>'warehouseId', created_at DESC" +
            "    ) sub" +
            ")", nativeQuery = true)
    int deleteDuplicateStockNotifications();
}