package com.argus.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.intelligence.KnownUniverse;
import com.argus.intelligence.SentimentLabel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A malformed or unpersistable post (a stray NUL byte, an oversized field) must cost only itself, never the rest of the cycle. */
class SocialIngestionServiceTest {

	private final SocialSource source = mock(SocialSource.class);
	private final SocialPostRepository posts = mock(SocialPostRepository.class);
	private final KnownUniverse universe = mock(KnownUniverse.class);
	private final SocialIngestionService service = new SocialIngestionService(List.of(source), posts, universe);

	private static RawSocialPost post(String id, String body) {
		return new RawSocialPost("stocktwits", id, "AAPL", "trader1", body, "https://x", Instant.now(), null);
	}

	{
		when(universe.knownTickers()).thenReturn(java.util.Set.of("AAPL"));
		when(posts.existsBySourceAndExternalId(anyString(), anyString())).thenReturn(false);
	}

	@Test
	void aNulByteInOnePostsBodyIsStrippedBeforeItEverReachesTheDatabase() {
		when(source.fetch(any())).thenReturn(List.of(post("1", "to the moon \u0000 !!!")));

		service.ingestOnce();

		ArgumentCaptor<SocialPost> saved = ArgumentCaptor.forClass(SocialPost.class);
		verify(posts).save(saved.capture());
		assertEquals("to the moon  !!!", saved.getValue().getBody(), "the NUL is gone, the rest of the text is untouched");
	}

	@Test
	void aPostThatFailsToPersistDoesNotCostTheRestOfTheBatch() {
		when(source.fetch(any())).thenReturn(List.of(post("bad", "will fail to persist"), post("good", "a normal post")));
		when(posts.save(any())).thenThrow(new RuntimeException("invalid byte sequence for encoding \"UTF8\": 0x00"))
				.thenAnswer(inv -> inv.getArgument(0));

		service.ingestOnce();

		verify(posts, times(2)).save(any());
	}

	@Test
	void oneSourceFailingLeavesTheOthersAndTheRestOfTheCycleIntact() {
		SocialSource broken = mock(SocialSource.class);
		when(broken.fetch(any())).thenThrow(new RuntimeException("stocktwits down"));
		when(source.fetch(any())).thenReturn(List.of(post("1", "still works")));
		SocialIngestionService withTwoSources = new SocialIngestionService(List.of(broken, source), posts, universe);

		withTwoSources.ingestOnce();

		verify(posts).save(any());
	}

	@Test
	void nothingIsFetchedWhenThereAreNoHeldTickersOrNoSources() {
		when(universe.knownTickers()).thenReturn(java.util.Set.of());

		service.ingestOnce();

		verify(posts, never()).save(any());
		verify(source, never()).fetch(any());
	}

	@Test
	void aDuplicateExternalIdIsSkipped() {
		when(source.fetch(any())).thenReturn(List.of(post("dup", "seen before")));
		when(posts.existsBySourceAndExternalId(eq("stocktwits"), eq("dup"))).thenReturn(true);

		service.ingestOnce();

		verify(posts, never()).save(any());
	}
}
