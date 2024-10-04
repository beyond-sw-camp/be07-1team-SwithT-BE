package com.tweety.SwithT.lecture_apply.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tweety.SwithT.common.domain.Status;
import com.tweety.SwithT.common.dto.CommonResDto;
import com.tweety.SwithT.common.dto.MemberNameResDto;
import com.tweety.SwithT.common.service.MemberFeign;
import com.tweety.SwithT.common.service.RedisStreamProducer;
import com.tweety.SwithT.lecture.domain.Lecture;
import com.tweety.SwithT.lecture.domain.LectureGroup;
import com.tweety.SwithT.lecture.dto.TuteeMyLectureListResDto;
import com.tweety.SwithT.lecture.repository.LectureGroupRepository;
import com.tweety.SwithT.lecture.repository.LectureRepository;
import com.tweety.SwithT.lecture_apply.domain.LectureApply;
import com.tweety.SwithT.lecture_apply.dto.*;
import com.tweety.SwithT.lecture_apply.repository.LectureApplyRepository;
import com.tweety.SwithT.lecture_chat_room.domain.LectureChatParticipants;
import com.tweety.SwithT.lecture_chat_room.domain.LectureChatRoom;
import com.tweety.SwithT.lecture_chat_room.repository.LectureChatParticipantsRepository;
import com.tweety.SwithT.lecture_chat_room.repository.LectureChatRoomRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;


@Service
@RequiredArgsConstructor
public class LectureApplyService {
    private final LectureGroupRepository lectureGroupRepository;
    private final LectureApplyRepository lectureApplyRepository;
    private final LectureChatRoomRepository lectureChatRoomRepository;
    private final LectureChatParticipantsRepository lectureChatParticipantsRepository;
    private final LectureRepository lectureRepository;
    private final MemberFeign memberFeign;
    private final RedisStreamProducer redisStreamProducer;
    private final WaitingService waitingService;


    @Value("${jwt.secretKey}")
    private String secretKey;

    //튜티가 과외 신청
    @Transactional
    public SingleLectureApplyAfterResDto tuteeSingleLectureApply(SingleLectureApplySavedDto dto) {
        Long memberId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());

        CommonResDto commonResDto = memberFeign.getMemberNameById(memberId);
        ObjectMapper objectMapper = new ObjectMapper();
        MemberNameResDto memberNameResDto = objectMapper.convertValue(commonResDto.getResult(), MemberNameResDto.class);
        String memberName = memberNameResDto.getName();


        LectureGroup lectureGroup = lectureGroupRepository.findByIdAndDelYn(dto.getLectureGroupId(), "N").orElseThrow(()->{
            throw new EntityNotFoundException("해당 과외는 존재하지 않습니다.");
        });
        if(lectureGroup.getIsAvailable().equals("N")){
            throw new RuntimeException("해당 과외는 신청할 수 없습니다.");
        }

        List<LectureApply> lectureApplyList = lectureApplyRepository.findByMemberIdAndLectureGroup(memberId, lectureGroup);
        if(!lectureApplyList.isEmpty()){
            int rejectedCount = 0;
            for(LectureApply lectureApply : lectureApplyList){
                if(lectureApply.getStatus() == Status.STANDBY){
                    throw new RuntimeException("이미 신청한 과외입니다.");
                }
                if(lectureApply.getStatus() == Status.REJECT){
                    rejectedCount++;
                    if(rejectedCount>=3){
                        throw new RuntimeException("해당 과외는 3회 이상 거절되어 신청할 수 없습니다.");
                    }
                }
            }
        }
        lectureApplyRepository.save(dto.toEntity(lectureGroup, memberId, memberName));

        Lecture lecture = lectureRepository.findByIdAndDelYn(lectureGroup.getLecture().getId(), "N").orElseThrow(()->{
            throw new EntityNotFoundException("해당 과외가 존재하지 않습니다.");
        });

        return SingleLectureApplyAfterResDto.builder().lectureTitle(lecture.getTitle()).build();


    }

    //튜터가 보는 강의그룹 신청자 리스트
    public Page<SingleLectureApplyListDto> singleLectureApplyList(Long id, Pageable pageable) {
        Long memberId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());

        LectureGroup lectureGroup = lectureGroupRepository.findByIdAndDelYn(id, "N").orElseThrow(()->{
            throw new EntityNotFoundException("해당 강의 그룹이 없습니다");
        });
        Lecture lecture = lectureGroup.getLecture();
        if(lecture.getMemberId() != memberId){  //소유자가 아닌 경우
            throw new IllegalArgumentException("접근할 수 없는 강의 그룹입니다");
        }
        List<LectureApply> lectureApplyList = lectureApplyRepository.findByLectureGroupAndStatusAndDelYn(lectureGroup, Status.WAITING, "N");
        List<LectureApply> lectureApplyStandbyList = lectureApplyRepository.findByLectureGroupAndStatusAndDelYn(lectureGroup, Status.STANDBY, "N");
        lectureApplyList.addAll(lectureApplyStandbyList);
        PageRequest pageRequest = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        int start = (int) pageRequest.getOffset();
        int end = Math.min((start + pageRequest.getPageSize()), lectureApplyList.size());
        Page<LectureApply> lectureApplyPage = new PageImpl<>(lectureApplyList.subList(start, end), pageRequest, lectureApplyList.size());
        Page<SingleLectureApplyListDto> result = lectureApplyPage.map(a->a.fromEntityToSingleLectureApplyListDto());
        for(SingleLectureApplyListDto dto : result){
            // chatroomlist
            List<LectureChatRoom> lectureChatRoomList = lectureChatRoomRepository.findByLectureGroupAndDelYn(lectureGroup,"N");
            for(LectureChatRoom chatRoom : lectureChatRoomList){
                Long roomId = chatRoom.getId();
                if(lectureChatParticipantsRepository.findByLectureChatRoomIdAndMemberIdAndDelYn(roomId,dto.getMemberId(),"N" ).isEmpty()){
                    dto.setChatRoomId(null);
                }
                else{
                    dto.setChatRoomId(roomId);
                }
            }
        }
        return result;
    }

    //튜터 - 튜티의 신청 승인
    @Transactional
    public String singleLecturePaymentRequest(Long id) {
        LectureApply lectureApply = lectureApplyRepository.findByIdAndDelYn(id, "N").orElseThrow(()->{
            throw new EntityNotFoundException("id에 맞는 수강을 찾을 수 없습니다.");
        });
        LectureGroup lectureGroup = lectureApply.getLectureGroup();

        if(!lectureApplyRepository.findByLectureGroupAndStatusAndDelYn(lectureGroup, Status.WAITING, "N").isEmpty()){
            throw new IllegalArgumentException("결제 대기 중인 튜티가 존재합니다.");
        }

        lectureApply.updateStatus(Status.WAITING);

        //결제 요청 보내기
        redisStreamProducer.publishMessage(lectureApply.getMemberId().toString(), "결제요청", "수학천재가 되는 길에서 결제 요청을 했습니다.", lectureApply.getId().toString());


        return "튜터가 해당 수강신청을 승인했습니다.";
    }

    //튜터 - 튜티의 신청 거절
    @Transactional
    public String singleLectureApplyReject(Long id) {
        LectureApply lectureApply = lectureApplyRepository.findByIdAndDelYn(id, "N").orElseThrow(()->{
            throw new EntityNotFoundException("id에 맞는 수강을 찾을 수 없습니다.");
        });

        lectureApply.updateStatus(Status.REJECT);
        return "튜터가 해당 수강신청을 거절했습니다.";

    }

    //튜티 - 내 강의 리스트
    public Page<TuteeMyLectureListResDto> myLectureList(String status, Pageable pageable) {
        Long memberId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());

        Specification<LectureApply> specification = new Specification<LectureApply>() {
            @Override
            public Predicate toPredicate(Root<LectureApply> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder) {
                List<Predicate> predicates = new ArrayList<>();
                predicates.add(criteriaBuilder.equal(root.get("memberId"), memberId));
                predicates.add(criteriaBuilder.equal(root.get("delYn"), "N"));

                if (status != null && !status.isEmpty()) {
                    predicates.add(criteriaBuilder.equal(root.get("status"), status));
                }
                Predicate[] predicateArr = new Predicate[predicates.size()];
                for(int i=0; i<predicateArr.length; i++){
                    predicateArr[i] = predicates.get(i);
                }
                return criteriaBuilder.and(predicateArr);
            }
        };
        Page<LectureApply> lectureApplyPage = lectureApplyRepository.findAll(specification, pageable);
        List<TuteeMyLectureListResDto> tuteeMyLectureListResDtos = new ArrayList<>();
        for(LectureApply lectureApply : lectureApplyPage){
            tuteeMyLectureListResDtos.add(TuteeMyLectureListResDto.builder()
                            .title(lectureApply.getLectureGroup().getLecture().getTitle())
                            .startDate(lectureApply.getStartDate())
                            .endDate(lectureApply.getEndDate())
                            .tutorName(lectureApply.getLectureGroup().getLecture().getMemberName())
                            .price(lectureApply.getLectureGroup().getPrice())
                            .applyId(lectureApply.getId())
                            .lectureGroupId(lectureApply.getLectureGroup().getId())
                            .status(lectureApply.getStatus())
                    .build());
        }


        PageRequest pageRequest = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        int start = (int) pageRequest.getOffset();
        int end = Math.min((start + pageRequest.getPageSize()), tuteeMyLectureListResDtos.size());
        return new PageImpl<>(tuteeMyLectureListResDtos.subList(start, end), pageRequest, tuteeMyLectureListResDtos.size());

    }

    // 강의 신청
    @Transactional
    public LectureApplyAfterResDto tuteeLectureApply(LectureApplySavedDto dto) throws InterruptedException {

        Long memberId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        CommonResDto commonResDto = memberFeign.getMemberNameById(memberId);
        ObjectMapper objectMapper = new ObjectMapper();
        MemberNameResDto memberNameResDto = objectMapper.convertValue(commonResDto.getResult(), MemberNameResDto.class);
        String memberName = memberNameResDto.getName();

        LectureGroup lectureGroup = lectureGroupRepository.findByIdAndDelYn(dto.getLectureGroupId(), "N").orElseThrow(() -> {
            throw new EntityNotFoundException("해당 강의는 존재하지 않습니다.");
        });

        if (lectureGroup.getIsAvailable().equals("N")) {
            throw new RuntimeException("해당 강의는 신청할 수 없습니다.");
        }

        List<LectureApply> lectureApplyList = lectureApplyRepository.findByMemberIdAndLectureGroup(memberId, lectureGroup);
        if(!lectureApplyList.isEmpty()) {
            int rejectedCount = 0;
            for (LectureApply lectureApply : lectureApplyList) {
                if (lectureApply.getStatus() == Status.STANDBY) {
                    throw new RuntimeException("이미 신청한 과외입니다.");
                }
            }
        }

        // 강의 신청
        waitingService.addQueue(dto.getLectureGroupId(), memberId);
        // 대기열 순번 표출
        waitingService.getOrder(memberId.toString(), dto.getLectureGroupId().toString());
        // 결제로 넘기기
        waitingService.processPayment(lectureGroup);

        lectureApplyRepository.save(dto.toEntity(lectureGroup, memberId, memberName));

        return LectureApplyAfterResDto.builder().lectureGroupId(lectureGroup.getId()).build();
    }


    @Transactional
    public String testTuteeLectureApply(LectureApplySavedDto dto, LectureGroup lectureGroup, Long memberId, String memberName, Long lectureGroupId, int limitPeople) throws InterruptedException {

//        Long memberId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
//        CommonResDto commonResDto = memberFeign.getMemberNameById(memberId);
//        ObjectMapper objectMapper = new ObjectMapper();
//        MemberNameResDto memberNameResDto = objectMapper.convertValue(commonResDto.getResult(), MemberNameResDto.class);
//        String memberName = memberNameResDto.getName();

//        LectureGroup lectureGroup = lectureGroupRepository.findByIdAndDelYn(dto.getLectureGroupId(), "N").orElseThrow(() -> {
//            throw new EntityNotFoundException("해당 강의는 존재하지 않습니다.");
//        });
//
//        if (lectureGroup.getIsAvailable().equals("N")) {
//            throw new RuntimeException("해당 강의는 신청할 수 없습니다.");
//        }
//        List<LectureApply> lectureApplyList = lectureApplyRepository.findByMemberIdAndLectureGroup(memberId, lectureGroup);
//        if(!lectureApplyList.isEmpty()) {
//            int rejectedCount = 0;
//            for (LectureApply lectureApply : lectureApplyList) {
//                if (lectureApply.getStatus() == Status.STANDBY) {
//                    throw new RuntimeException("이미 신청한 과외입니다.");
//                }
//            }
//        }

        // 강의 신청
        waitingService.addQueue(dto.getLectureGroupId(), memberId);

        // 순번 표출
        waitingService.getOrder(memberId.toString(), dto.getLectureGroupId().toString());

        // 결제로 넘기기
        waitingService.processPayment(lectureGroup);

        LectureApply lectureApply = lectureApplyRepository.save(dto.toEntity(lectureGroup, memberId, memberName));

        return lectureGroup.getId()+"번 강의에 수강 신청되었습니다.";
    }
}
