package com.tweety.SwithT.lecture_apply.domain;

import com.tweety.SwithT.common.domain.BaseTimeEntity;
import com.tweety.SwithT.common.domain.Status;
import com.tweety.SwithT.lecture.domain.Lecture;
import com.tweety.SwithT.lecture.domain.LectureGroup;
import com.tweety.SwithT.lecture.domain.LectureType;
import com.tweety.SwithT.lecture_apply.dto.SingleLectureApplyListDto;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
public class LectureApply extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_group_id")
    private LectureGroup lectureGroup;

    @Column(nullable = false)
    private Long memberId;

    //신청한 튜티의 이름
    @Column(nullable = false)
    private String memberName;

    @Column(nullable = true)
    private LocalDate startDate;

    @Column(nullable = true)
    private LocalDate endDate;

    @Column(nullable = true)
    private String location;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private Status status = Status.STANDBY;


    public SingleLectureApplyListDto fromEntityToSingleLectureApplyListDto(){
        return SingleLectureApplyListDto.builder()
                .tuteeName(this.memberName)
                .memberId(this.memberId)
                .applyId(this.id)
                .status(this.status)
                .build();
    }


    public void updateStatus(Status updateStatus){
        this.status = updateStatus;
    }


}
