package com.projectplanner.schedule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DependencyGraphTest {

    // 1 -> 2 -> 3, 1 -> 4, 5 on its own
    private final DependencyGraph graph = new DependencyGraph(List.of(1L, 2L, 3L, 4L, 5L),
            List.of(new Link(1, 2), new Link(2, 3), new Link(1, 4)));

    @Test
    void detectsDirectCycle() {
        assertThat(graph.wouldCreateCycle(2, 1)).isTrue();
    }

    @Test
    void detectsIndirectCycle() {
        assertThat(graph.wouldCreateCycle(3, 1)).isTrue();
    }

    @Test
    void selfDependencyIsACycle() {
        assertThat(graph.wouldCreateCycle(5, 5)).isTrue();
    }

    @Test
    void allowsLinksThatKeepTheGraphAcyclic() {
        assertThat(graph.wouldCreateCycle(4, 3)).isFalse();
        assertThat(graph.wouldCreateCycle(5, 1)).isFalse();
        assertThat(graph.wouldCreateCycle(1, 3)).isFalse();
    }

    @Test
    void reachableFollowsChains() {
        assertThat(graph.reachable(1)).containsExactlyInAnyOrder(2L, 3L, 4L);
        assertThat(graph.reachable(3)).isEmpty();
    }

    @Test
    void pathFindsTheChain() {
        assertThat(graph.path(1, 3)).containsExactly(1L, 2L, 3L);
        assertThat(graph.path(3, 1)).isEmpty();
    }

    @Test
    void topologicalOrderPutsPredecessorsFirst() {
        List<Long> order = graph.topologicalOrder();
        assertThat(order).hasSize(5);
        assertThat(order.indexOf(1L)).isLessThan(order.indexOf(2L));
        assertThat(order.indexOf(2L)).isLessThan(order.indexOf(3L));
        assertThat(order.indexOf(1L)).isLessThan(order.indexOf(4L));
    }

    @Test
    void topologicalOrderRejectsCycles() {
        DependencyGraph cyclic = new DependencyGraph(List.of(1L, 2L), List.of(new Link(1, 2), new Link(2, 1)));
        assertThatThrownBy(cyclic::topologicalOrder).isInstanceOf(IllegalStateException.class);
    }
}
