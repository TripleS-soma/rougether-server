package com.triples.rougether.userapi.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.chat.entity.ChatMessageType;
import com.triples.rougether.domain.chat.entity.ChatReactionType;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.userapi.chat.dto.ChatMessageImageResponse;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatSendRequest;
import com.triples.rougether.userapi.chat.realtime.ChatSocketHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

// 사진 여러 장, 사진+글, 이모티콘 전송과 사진 자르기 업로드.
class ChatRichMessageIntegrationTest extends ChatRichTestSupport {

    ResultActions postMessage(Fixture f, Long user, String body) throws Exception {
        return mvc.perform(post("/api/v1/chat/rooms/" + f.room() + "/messages").header("Authorization", auth(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    String json(String fields) { return "{\"clientMessageId\":\"" + UUID.randomUUID() + "\"" + fields + "}"; }

    List<Long> attachedIds(Long messageId) {
        return jdbc.queryForList("select id from chat_images where message_id = ? order by sort_order", Long.class, messageId);
    }

    @Test
    void 사진_여러_장을_한_메시지로_보내면_요청_순서대로_저장되고_image는_첫_장이다() throws Exception {
        Fixture f = fixture();
        List<Long> uploadedIds = uploaded(f, f.owner(), 3);
        // 올린 순서와 다르게 보내 요청 순서가 보존되는지 확인함.
        List<Long> order = List.of(uploadedIds.get(2), uploadedIds.get(0), uploadedIds.get(1));

        var sent = sendPhotos(f, f.owner(), null, order);

        assertThat(sent.messageType()).isEqualTo(ChatMessageType.IMAGE);
        assertThat(sent.content()).isEqualTo("사진 3장을 보냈어요");
        assertThat(sent.caption()).isNull();
        assertThat(sent.emoticonKey()).isNull();
        assertThat(sent.reactions()).isEmpty();
        assertThat(sent.images()).extracting(ChatMessageImageResponse::imageId).containsExactlyElementsOf(order);
        assertThat(sent.image()).isEqualTo(new ChatMessageImageResponse(order.getFirst(), 48, 32));
        assertThat(attachedIds(sent.messageId())).containsExactlyElementsOf(order);
        assertThat(jdbc.queryForList("select sort_order from chat_images where message_id = ? order by sort_order",
                Integer.class, sent.messageId())).containsExactly(0, 1, 2);
        assertThat(messageCount(f)).isEqualTo(1);

        var history = query.messages(f.member(), f.room(), null, null, 50).items().getFirst();
        assertSameMessage(history, sent);
        var socket = mapper.readTree(mapper.writeValueAsString(new ChatSocketHandler.MessageCreated("MESSAGE_CREATED", history)));
        assertThat(socket.at("/message/images/0/imageId").asLong()).isEqualTo(order.get(0));
        assertThat(socket.at("/message/images/2/imageId").asLong()).isEqualTo(order.get(2));
        assertThat(socket.at("/message/image/imageId").asLong()).isEqualTo(order.get(0));
        // 보낸 사진은 장마다 구성원이 볼 수 있음.
        for (Long imageId : order) assertThat(images.read(f.member(), imageId)).isNotEmpty();
    }

    @Test
    void 사진은_10장까지_보낼_수_있고_11장과_중복_ID는_거부한다() throws Exception {
        Fixture f = fixture();
        List<Long> ten = uploaded(f, f.owner(), 10);
        Long extra = uploaded(f, f.owner());
        List<Long> eleven = new ArrayList<>(ten);
        eleven.add(extra);

        error(() -> sendPhotos(f, f.owner(), null, eleven), "CHAT_INPUT_INVALID");
        error(() -> sendPhotos(f, f.owner(), null, List.of(extra, extra)), "CHAT_INPUT_INVALID");
        error(() -> sendPhotos(f, f.owner(), null, Collections.singletonList((Long) null)), "CHAT_INPUT_INVALID");
        assertThat(messageCount(f)).isZero();
        assertThat(imageRows.findById(extra).orElseThrow().getMessageId()).isNull();

        var sent = sendPhotos(f, f.owner(), null, ten);
        assertThat(sent.images()).hasSize(10);
        assertThat(sent.content()).isEqualTo("사진 10장을 보냈어요");
    }

    @Test
    void 한_장이라도_쓸_수_없으면_전체가_실패하고_다른_사진은_다시_쓸_수_있다() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        tx.executeWithoutResult(s -> members.save(HouseMember.create(houses.getReferenceById(other.house()),
                users.getReferenceById(f.owner()), HouseMemberRole.MEMBER)));
        Long mine = uploaded(f, f.owner());
        Long second = uploaded(f, f.owner());
        Long someoneElses = uploaded(f, f.member());
        Long otherRoom = uploaded(other, f.owner());
        Long used = uploaded(f, f.owner());
        sendPhotos(f, f.owner(), null, List.of(used));

        for (Long bad : List.of(someoneElses, otherRoom, used, Long.MAX_VALUE)) {
            error(() -> sendPhotos(f, f.owner(), null, List.of(mine, bad, second)), "CHAT_IMAGE_UNAVAILABLE");
            error(() -> sendPhotos(f, f.owner(), null, List.of(bad, mine)), "CHAT_IMAGE_UNAVAILABLE");
        }

        assertThat(messageCount(f)).isEqualTo(1);
        assertThat(imageRows.findById(mine).orElseThrow().getMessageId()).isNull();
        assertThat(imageRows.findById(second).orElseThrow().getMessageId()).isNull();
        assertThat(imageRows.findById(someoneElses).orElseThrow().getMessageId()).isNull();
        // 실패한 요청에 들어 있던 사진은 그대로 남아 다음 전송에 쓸 수 있음.
        assertThat(sendPhotos(f, f.owner(), null, List.of(mine, second)).images()).hasSize(2);
    }

    @Test
    void 사진과_글을_한_메시지로_보내면_글이_설명_글과_구버전_표시_문구가_된다() throws Exception {
        Fixture f = fixture();
        List<Long> ids = uploaded(f, f.owner(), 2);

        var sent = sendPhotos(f, f.owner(), "오늘 아침 산책", ids);

        assertThat(sent.messageType()).isEqualTo(ChatMessageType.IMAGE);
        assertThat(sent.caption()).isEqualTo("오늘 아침 산책");
        assertThat(sent.content()).isEqualTo("오늘 아침 산책");
        assertThat(sent.images()).hasSize(2);
        assertThat(jdbc.queryForObject("select caption from chat_messages where id = ?", String.class, sent.messageId()))
                .isEqualTo("오늘 아침 산책");
        // 설명 글이 있어도 사진 메시지는 AI 사용자 이벤트를 보내지 않음.
        verify(aiEvents, never()).chat(any(), anyLong(), anyLong());
    }

    @Test
    void 설명_글에도_금칙어_검사를_적용하고_거부되면_사진은_그대로_남는다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());
        doReturn(true).when(bannedWords).containsBannedWord("금칙어가 든 설명");

        error(() -> sendPhotos(f, f.owner(), "금칙어가 든 설명", List.of(imageId)), "CHAT_CONTENT_BANNED");
        mvc.perform(post("/api/v1/chat/rooms/" + f.room() + "/messages").header("Authorization", auth(f.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(photos(UUID.randomUUID(), "금칙어가 든 설명", List.of(imageId)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHAT_CONTENT_BANNED"));

        assertThat(messageCount(f)).isZero();
        assertThat(imageRows.findById(imageId).orElseThrow().getMessageId()).isNull();
    }

    @Test
    void 구버전_단일_imageId_요청은_그대로_동작한다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());

        postMessage(f, f.owner(), json(",\"imageId\":" + imageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageType").value("IMAGE"))
                .andExpect(jsonPath("$.content").value("사진을 보냈어요"))
                .andExpect(jsonPath("$.image.imageId").value(imageId))
                .andExpect(jsonPath("$.images.length()").value(1))
                .andExpect(jsonPath("$.images[0].imageId").value(imageId))
                .andExpect(jsonPath("$.caption").doesNotExist())
                .andExpect(jsonPath("$.emoticonKey").doesNotExist())
                .andExpect(jsonPath("$.reactions.length()").value(0));
    }

    @Test
    void 글_메시지_응답은_기존_필드가_그대로이고_새_필드는_비어_있다() throws Exception {
        Fixture f = fixture();

        postMessage(f, f.owner(), json(",\"content\":\"안녕\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageType").value("TEXT"))
                .andExpect(jsonPath("$.content").value("안녕"))
                .andExpect(jsonPath("$.image").doesNotExist())
                .andExpect(jsonPath("$.images.length()").value(0))
                .andExpect(jsonPath("$.reactions.length()").value(0));
        verify(aiEvents).chat(any(), anyLong(), anyLong());
    }

    @Test
    void 허용하지_않는_조합은_모두_400이다() throws Exception {
        Fixture f = fixture();
        List<Long> ids = uploaded(f, f.owner(), 2);
        Long a = ids.get(0);
        Long b = ids.get(1);
        for (String fields : List.of(
                "",                                                                  // 아무것도 없음
                ",\"content\":\"  \"",                                              // 공백만
                ",\"imageId\":" + a + ",\"imageIds\":[" + b + "]",                 // 단일·목록 동시
                ",\"imageId\":" + a + ",\"imageIds\":[" + a + "]",
                ",\"imageIds\":[" + a + "," + a + "]",                             // 중복
                ",\"imageIds\":[" + a + ",null]",
                ",\"imageIds\":[" + a + "],\"content\":\" \"",                     // 설명 글이 공백만
                ",\"emoticonKey\":\"ruti_hello\",\"content\":\"글\"",              // 이모티콘 + 글
                ",\"emoticonKey\":\"ruti_hello\",\"imageIds\":[" + a + "]",        // 이모티콘 + 사진
                ",\"emoticonKey\":\"ruti_hello\",\"imageId\":" + a,
                ",\"emoticonKey\":\"\"",
                ",\"emoticonKey\":\"Ruti\"",                                       // 대문자
                ",\"emoticonKey\":\"_ruti\"",                                      // 첫 글자
                ",\"emoticonKey\":\"ruti hello\"",
                ",\"emoticonKey\":\"" + "a".repeat(41) + "\"")) {                  // 41자
            postMessage(f, f.owner(), json(fields))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CHAT_INPUT_INVALID"));
        }
        assertThat(messageCount(f)).isZero();
        assertThat(imageRows.findById(a).orElseThrow().getMessageId()).isNull();
    }

    @Test
    void 이모티콘_메시지는_key와_구버전_표시_문구를_담는다() throws Exception {
        Fixture f = fixture();

        postMessage(f, f.owner(), json(",\"emoticonKey\":\"ruti_hello-2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageType").value("EMOTICON"))
                .andExpect(jsonPath("$.emoticonKey").value("ruti_hello-2"))
                .andExpect(jsonPath("$.content").value("이모티콘을 보냈어요"))
                .andExpect(jsonPath("$.image").doesNotExist())
                .andExpect(jsonPath("$.images.length()").value(0));
        var longest = commands.send(f.owner(), f.room(), emoticon(UUID.randomUUID(), "a".repeat(40)));
        assertThat(longest.emoticonKey()).hasSize(40);

        var items = query.messages(f.member(), f.room(), null, null, 50).items();
        assertThat(items.getLast().messageType()).isEqualTo(ChatMessageType.EMOTICON);
        assertThat(items.getLast().emoticonKey()).isEqualTo("ruti_hello-2");
        verify(aiEvents, never()).chat(any(), anyLong(), anyLong());
    }

    @Test
    void 같은_요청_ID_재전송은_내용이_모두_같을_때만_처음_메시지를_돌려준다() throws Exception {
        Fixture f = fixture();
        List<Long> ids = uploaded(f, f.owner(), 3);
        Long a = ids.get(0);
        Long b = ids.get(1);
        Long spare = ids.get(2);
        UUID photoId = UUID.randomUUID();

        var sent = commands.send(f.owner(), f.room(), photos(photoId, "설명", List.of(a, b)));
        var retried = commands.send(f.owner(), f.room(), photos(photoId, "설명", List.of(a, b)));

        assertSameMessage(retried, sent);
        assertThat(messageCount(f)).isEqualTo(1);
        for (ChatSendRequest different : List.of(
                photos(photoId, "설명", List.of(b, a)),              // 사진 순서
                photos(photoId, "설명", List.of(a)),                 // 사진 수
                photos(photoId, "설명", List.of(a, b, spare)),
                photos(photoId, "다른 설명", List.of(a, b)),         // 설명 글
                photos(photoId, null, List.of(a, b)),                // 설명 글 없음
                new ChatSendRequest(photoId, "설명"),                // 유형
                emoticon(photoId, "ruti_hello"))) {
            error(() -> commands.send(f.owner(), f.room(), different), "CHAT_MESSAGE_CONFLICT");
        }
        assertThat(imageRows.findById(spare).orElseThrow().getMessageId()).isNull();

        // 설명 글 없이 보낸 메시지에 설명 글을 붙여 다시 보내면 충돌함.
        UUID plainId = UUID.randomUUID();
        commands.send(f.owner(), f.room(), photos(plainId, null, List.of(spare)));
        error(() -> commands.send(f.owner(), f.room(), photos(plainId, "설명", List.of(spare))), "CHAT_MESSAGE_CONFLICT");
        // 구버전 단일 필드와 목록 한 장은 같은 요청임.
        assertThat(commands.send(f.owner(), f.room(), new ChatSendRequest(plainId, null, spare)).images()).hasSize(1);

        UUID emoticonId = UUID.randomUUID();
        var emoticonSent = commands.send(f.owner(), f.room(), emoticon(emoticonId, "ruti_hello"));
        assertThat(commands.send(f.owner(), f.room(), emoticon(emoticonId, "ruti_hello")).messageId())
                .isEqualTo(emoticonSent.messageId());
        error(() -> commands.send(f.owner(), f.room(), emoticon(emoticonId, "ruti_bye")), "CHAT_MESSAGE_CONFLICT");
        error(() -> commands.send(f.owner(), f.room(), new ChatSendRequest(emoticonId, "이모티콘을 보냈어요")),
                "CHAT_MESSAGE_CONFLICT");
        assertThat(messageCount(f)).isEqualTo(3);
    }

    @Test
    void 운영자가_지운_여러_장_메시지는_사진이_모두_막히고_정리되며_새_필드가_비워진다() throws Exception {
        Fixture f = fixture();
        List<Long> ids = uploaded(f, f.owner(), 3);
        UUID requestId = UUID.randomUUID();
        var sent = commands.send(f.owner(), f.room(), photos(requestId, "지워질 설명", ids));
        reactions.react(f.member(), f.room(), sent.messageId(), ChatReactionType.HEART);
        var sticker = commands.send(f.owner(), f.room(), emoticon(UUID.randomUUID(), "ruti_hello"));

        deleteMessage(sent.messageId());
        deleteMessage(sticker.messageId());

        var items = query.messages(f.member(), f.room(), null, null, 50).items();
        ChatMessageResponse deleted = items.getLast();
        assertThat(deleted.content()).isEqualTo("삭제된 메시지예요");
        assertThat(deleted.messageType()).isEqualTo(ChatMessageType.IMAGE);
        assertThat(deleted.image()).isNull();
        assertThat(deleted.images()).isEmpty();
        assertThat(deleted.caption()).isNull();
        assertThat(deleted.reactions()).isEmpty();
        assertThat(items.getFirst().emoticonKey()).isNull();
        assertThat(items.getFirst().content()).isEqualTo("삭제된 메시지예요");
        for (Long imageId : ids) error(() -> images.read(f.member(), imageId), "CHAT_IMAGE_NOT_FOUND");
        // 삭제된 메시지를 같은 요청 ID로 다시 보내면 충돌 없이 삭제된 상태 그대로 돌려줌.
        var retried = commands.send(f.owner(), f.room(), photos(requestId, "지워질 설명", ids));
        assertThat(retried.messageId()).isEqualTo(sent.messageId());
        assertThat(retried.images()).isEmpty();

        job.run();

        for (Long imageId : ids) assertThat(imageRows.findById(imageId)).isEmpty();
        assertThat(objects).isEmpty();
        // 원문은 신고 확인을 위해 DB에 남음.
        assertThat(jdbc.queryForObject("select caption from chat_messages where id = ?", String.class, sent.messageId()))
                .isEqualTo("지워질 설명");
    }

    @Test
    void 탈퇴한_사람의_여러_장_사진은_응답에서_빠지고_문구는_남는다() throws Exception {
        Fixture f = fixture();
        var sent = sendPhotos(f, f.member(), null, uploaded(f, f.member(), 2));

        withdrawal.withdraw(f.member());

        var item = query.messages(f.owner(), f.room(), null, null, 50).items().getFirst();
        assertThat(item.messageId()).isEqualTo(sent.messageId());
        assertThat(item.images()).isEmpty();
        assertThat(item.image()).isNull();
        assertThat(item.content()).isEqualTo("사진 2장을 보냈어요");
    }

    @Test
    void 기록_조회의_쿼리_수는_메시지와_사진과_공감_수에_비례하지_않는다() throws Exception {
        Fixture f = fixture();
        var first = sendPhotos(f, f.owner(), null, uploaded(f, f.owner(), 2));
        reactions.react(f.member(), f.room(), first.messageId(), ChatReactionType.LIKE);
        long few = statementsDuring(() -> query.messages(f.member(), f.room(), null, null, 50));

        for (int i = 0; i < 5; i++) {
            var more = sendPhotos(f, f.owner(), "설명 " + i, uploaded(f, f.owner(), 3));
            reactions.react(f.member(), f.room(), more.messageId(), ChatReactionType.HEART);
            reactions.react(f.owner(), f.room(), more.messageId(), ChatReactionType.SAD);
            commands.send(f.member(), f.room(), emoticon(UUID.randomUUID(), "ruti_hello"));
        }
        long many = statementsDuring(() -> query.messages(f.member(), f.room(), null, null, 50));

        assertThat(many).isEqualTo(few);
        var items = query.messages(f.member(), f.room(), null, null, 50).items();
        assertThat(items).hasSize(11);
        assertThat(items.get(1).images()).hasSize(3);
        assertThat(items.get(1).reactions()).hasSize(2);
    }

    @Test
    void 푸시_문구는_설명_글_여러_장_한_장_이모티콘을_구분하고_영어도_맞춘다() throws Exception {
        Fixture f = fixture();

        sendPhotos(f, f.owner(), "가".repeat(150), uploaded(f, f.owner(), 2));
        verify(fcm, timeout(5_000)).pushWithoutRecord(eq(f.member()), eq("확장 테스트"),
                eq("보낸이: [사진] " + "가".repeat(100) + "…"), any());
        sendPhotos(f, f.owner(), null, uploaded(f, f.owner(), 3));
        verify(fcm, timeout(5_000)).pushWithoutRecord(eq(f.member()), eq("확장 테스트"), eq("보낸이: 사진 3장을 보냈어요"), any());
        sendPhotos(f, f.owner(), null, uploaded(f, f.owner(), 1));
        verify(fcm, timeout(5_000)).pushWithoutRecord(eq(f.member()), eq("확장 테스트"), eq("보낸이: 사진을 보냈어요"), any());
        commands.send(f.owner(), f.room(), emoticon(UUID.randomUUID(), "ruti_hello"));
        verify(fcm, timeout(5_000)).pushWithoutRecord(eq(f.member()), eq("확장 테스트"), eq("보낸이: 이모티콘을 보냈어요"), any());

        jdbc.update("update users set language = 'en' where id = ?", f.member());
        sendPhotos(f, f.owner(), "walk", uploaded(f, f.owner(), 1));
        verify(fcm, timeout(5_000)).pushWithoutRecord(eq(f.member()), eq("확장 테스트"), eq("보낸이: [Photo] walk"), any());
        sendPhotos(f, f.owner(), null, uploaded(f, f.owner(), 2));
        verify(fcm, timeout(5_000)).pushWithoutRecord(eq(f.member()), eq("확장 테스트"), eq("보낸이: sent 2 photos"), any());
        sendPhotos(f, f.owner(), null, uploaded(f, f.owner(), 1));
        verify(fcm, timeout(5_000)).pushWithoutRecord(eq(f.member()), eq("확장 테스트"), eq("보낸이: sent a photo"), any());
        commands.send(f.owner(), f.room(), emoticon(UUID.randomUUID(), "ruti_hello"));
        verify(fcm, timeout(5_000)).pushWithoutRecord(eq(f.member()), eq("확장 테스트"), eq("보낸이: sent an emoticon"), any());
    }

    ResultActions uploadCropped(Fixture f, int width, int height, String... crop) throws Exception {
        var request = multipart("/api/v1/chat/rooms/" + f.room() + "/images").file(png(width, height))
                .header("Authorization", auth(f.owner()));
        for (int i = 0; i + 1 < crop.length; i += 2) request.param(crop[i], crop[i + 1]);
        return mvc.perform(request);
    }

    @Test
    void 사진을_잘라_올리면_응답과_저장본이_자른_크기다() throws Exception {
        Fixture f = fixture();

        String body = uploadCropped(f, 400, 200, "cropX", "0.25", "cropY", "0.5", "cropWidth", "0.5", "cropHeight", "0.5")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.width").value(200))
                .andExpect(jsonPath("$.height").value(100))
                .andReturn().getResponse().getContentAsString();

        var row = imageRows.findById(mapper.readTree(body).path("imageId").asLong()).orElseThrow();
        assertThat(row.getWidth()).isEqualTo(200);
        assertThat(row.getHeight()).isEqualTo(100);
        var stored = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(objects.get(row.getStorageKey())));
        assertThat(stored.getWidth()).isEqualTo(200);
        assertThat(stored.getHeight()).isEqualTo(100);
        // 자르기를 생략하면 기존처럼 전체를 저장함. 전체 영역(0,0,1,1)도 같은 결과임.
        uploadCropped(f, 400, 200).andExpect(status().isCreated()).andExpect(jsonPath("$.width").value(400));
        uploadCropped(f, 400, 200, "cropX", "0", "cropY", "0", "cropWidth", "1", "cropHeight", "1")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.width").value(400))
                .andExpect(jsonPath("$.height").value(200));
    }

    @Test
    void 자르기_값이_일부만_있거나_범위를_벗어나거나_결과가_너무_작으면_400이고_저장하지_않는다() throws Exception {
        Fixture f = fixture();
        for (String[] crop : List.of(
                new String[] {"cropX", "0.1"},                                                              // 일부만
                new String[] {"cropX", "0.1", "cropY", "0.1", "cropWidth", "0.5"},
                new String[] {"cropX", "-0.1", "cropY", "0", "cropWidth", "0.5", "cropHeight", "0.5"},       // 범위 밖
                new String[] {"cropX", "1", "cropY", "0", "cropWidth", "0.5", "cropHeight", "0.5"},
                new String[] {"cropX", "0.6", "cropY", "0", "cropWidth", "0.5", "cropHeight", "0.5"},        // x+w > 1
                new String[] {"cropX", "0", "cropY", "0.2", "cropWidth", "0.5", "cropHeight", "0.9"},
                new String[] {"cropX", "0", "cropY", "0", "cropWidth", "0", "cropHeight", "0.5"},
                new String[] {"cropX", "0", "cropY", "0", "cropWidth", "1.5", "cropHeight", "0.5"},
                new String[] {"cropX", "abc", "cropY", "0", "cropWidth", "0.5", "cropHeight", "0.5"},
                new String[] {"cropX", "NaN", "cropY", "0", "cropWidth", "0.5", "cropHeight", "0.5"},
                new String[] {"cropX", "0", "cropY", "0", "cropWidth", "0.03", "cropHeight", "1"},           // 400 * 0.03 = 12px
                new String[] {"cropX", "0", "cropY", "0", "cropWidth", "1", "cropHeight", "0.05"})) {        // 200 * 0.05 = 10px
            uploadCropped(f, 400, 200, crop)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CHAT_IMAGE_INVALID"));
        }
        verify(storage, never()).put(any(), any());
        assertThat(jdbc.queryForObject("select count(*) from chat_images where room_id = ?", Long.class, f.room())).isZero();
        // 부동소수 오차로 합이 1을 아주 조금 넘는 값은 허용함.
        uploadCropped(f, 400, 200, "cropX", "0.7", "cropY", "0", "cropWidth", "0.30000000000000004", "cropHeight", "1")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.width").value(120));
    }
}
