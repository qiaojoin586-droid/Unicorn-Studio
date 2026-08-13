package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 上传文件记录（媒体库预留）。 */
@Entity
@Table(name = "upload_file")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 255)
    private String storedPath;

    @Column(nullable = false, length = 255)
    private String url;

    @Column(nullable = false)
    private Long size;

    @Column(length = 100)
    private String mimeType;

    private Long uploadedBy;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
