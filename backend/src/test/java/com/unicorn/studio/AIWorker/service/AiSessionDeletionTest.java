package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.AIWorker.entity.AiConversation;
import com.unicorn.studio.AIWorker.mapper.AiConversationMapper;
import com.unicorn.studio.AIWorker.mapper.AiMessageMapper;
import com.unicorn.studio.AIWorker.mapper.AiToolInvocationMapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AiSessionDeletionTest {

    private final AiConversationMapper conversations = mock(AiConversationMapper.class);
    private final AiMessageMapper messages = mock(AiMessageMapper.class);
    private final CacheHelper cache = mock(CacheHelper.class);
    private final AiSessionService service = new AiSessionService(
            conversations, messages, mock(AiToolInvocationMapper.class), cache);

    @Test
    void removesOwnHistoryAndInvalidatesBothCaches() {
        var conversation = new AiConversation();
        conversation.setId("owned-session");
        conversation.setUserId(7L);
        when(conversations.findOwned("owned-session", 7L)).thenReturn(conversation);
        when(messages.softDeleteByConversation("owned-session")).thenReturn(4);

        service.clearConversation("owned-session", 7L);

        verify(messages).softDeleteByConversation("owned-session");
        verify(cache).evictByPatternAfterCommit(CacheKeys.aiSessionsPattern(7L));
        verify(cache).evictAfterCommit(CacheKeys.aiMessages("owned-session"));
    }

    @Test
    void doesNotDeleteAnotherUsersHistory() {
        assertThatThrownBy(() -> service.clearConversation("someone-elses-session", 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(messages, cache);
    }
}
