package com.tweety.SwithT.board.dto.read;

import com.tweety.SwithT.board.domain.Board;
import com.tweety.SwithT.board.domain.Type;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
public class BoardListResDto {
    private Long id;
    private String memberName;
    private String title;
    private Type type;

    public static BoardListResDto fromEntity(Board board){
        return BoardListResDto.builder()
                .id(board.getId())
                .title(board.getTitle())
                .type(board.getType())
                .memberName(board.getMemberName())
                .build();
    }
}
