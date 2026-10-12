package com.springrag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordQueryTest {

    @Test
    void dropsStopWordsAndJoinsWithOr() {
        assertThat(KeywordQuery.build("What is the refund window for annual plans?"))
                .isEqualTo("refund | window | annual | plans");
    }

    @Test
    void lowercasesAndDeduplicates() {
        assertThat(KeywordQuery.build("Refund REFUND refund")).isEqualTo("refund");
    }

    @Test
    void neverLetsTsqueryOperatorsThrough() {
        String q = KeywordQuery.build("alpha'; DROP TABLE chunks; -- & ! ( ) : * <-> beta");

        assertThat(q).isEqualTo("alpha | drop | table | chunks | beta");
        assertThat(q).doesNotContain("&", "!", "(", ")", ":", "*", "<", "'", ";");
    }

    @Test
    void worksForCyrillic() {
        assertThat(KeywordQuery.build("Сколько стоит тариф Team?")).isEqualTo("стоит | тариф | team");
    }

    @Test
    void returnsNullWhenNothingUsable() {
        assertThat(KeywordQuery.build("what is the ?")).isNull();
        assertThat(KeywordQuery.build("")).isNull();
        assertThat(KeywordQuery.build("a b c")).isNull();
    }

    @Test
    void capsNumberOfTerms() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("term").append(i).append(' ');
        }
        assertThat(KeywordQuery.build(sb.toString()).split(" \\| ")).hasSize(24);
    }

    @Test
    void stopWordsOfOtherLanguagesAreDropped() {
        assertThat(KeywordQuery.build("Wie hoch ist der Preis für das Abonnement?")).isEqualTo("hoch | preis | abonnement");
        assertThat(KeywordQuery.build("Quel est le prix de l'abonnement ?")).isEqualTo("prix | abonnement");
        assertThat(KeywordQuery.build("¿Cuál es el precio de la suscripción?")).isEqualTo("precio | suscripción");
    }

    @Test
    void termsAreTheWordsOfTheQuery() {
        assertThat(KeywordQuery.terms("What is the refund window?")).containsExactly("refund", "window");
    }

    @Test
    void countMatchesCountsWholeWordsOnly() {
        List<String> terms = List.of("refund", "annual", "plan");

        assertThat(KeywordQuery.countMatches("Annual plans can be refunded.", terms)).isEqualTo(1); // "annual" only
        assertThat(KeywordQuery.countMatches("The REFUND for the annual plan", terms)).isEqualTo(3);
        assertThat(KeywordQuery.countMatches("nothing relevant", terms)).isZero();
    }
}
