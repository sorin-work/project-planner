package com.projectplanner.schedule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Directed graph of finish-to-start links between tasks. */
public final class DependencyGraph {

    private final Map<Long, List<Link>> outgoing = new HashMap<>();
    private final Map<Long, List<Link>> incoming = new HashMap<>();
    private final Set<Long> nodes = new LinkedHashSet<>();

    public DependencyGraph(Collection<Long> taskIds, Collection<Link> links) {
        nodes.addAll(taskIds);
        for (Link link : links) {
            nodes.add(link.predecessorId());
            nodes.add(link.successorId());
            outgoing.computeIfAbsent(link.predecessorId(), k -> new ArrayList<>()).add(link);
            incoming.computeIfAbsent(link.successorId(), k -> new ArrayList<>()).add(link);
        }
    }

    public List<Link> successors(long id) {
        return outgoing.getOrDefault(id, List.of());
    }

    public List<Link> predecessors(long id) {
        return incoming.getOrDefault(id, List.of());
    }

    /**
     * True if adding {@code predecessor -> successor} would close a loop, i.e. the
     * successor already leads (directly or indirectly) to the predecessor.
     */
    public boolean wouldCreateCycle(long predecessor, long successor) {
        return reachable(successor).contains(predecessor);
    }

    /** The chain of task ids from {@code from} to {@code to}, or an empty list if there is none. */
    public List<Long> path(long from, long to) {
        Map<Long, Long> cameFrom = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(from);
        cameFrom.put(from, from);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            if (current == to) {
                List<Long> path = new ArrayList<>();
                long step = to;
                while (step != from) {
                    path.addFirst(step);
                    step = cameFrom.get(step);
                }
                path.addFirst(from);
                return path;
            }
            for (Link link : successors(current)) {
                if (!cameFrom.containsKey(link.successorId())) {
                    cameFrom.put(link.successorId(), current);
                    queue.add(link.successorId());
                }
            }
        }
        return List.of();
    }

    /** Every task downstream of {@code start}, not including {@code start} itself. */
    public Set<Long> reachable(long start) {
        Set<Long> seen = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            long current = stack.pop();
            for (Link link : successors(current)) {
                if (seen.add(link.successorId())) {
                    stack.push(link.successorId());
                }
            }
        }
        seen.remove(start);
        return seen;
    }

    /** Kahn's algorithm. Throws if the graph has a cycle, which the API never lets in. */
    public List<Long> topologicalOrder() {
        Map<Long, Integer> inDegree = new HashMap<>();
        for (long node : nodes) {
            inDegree.put(node, predecessors(node).size());
        }
        Deque<Long> ready = new ArrayDeque<>();
        for (long node : nodes) {
            if (inDegree.get(node) == 0) {
                ready.add(node);
            }
        }
        List<Long> order = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            long node = ready.poll();
            order.add(node);
            for (Link link : successors(node)) {
                int remaining = inDegree.merge(link.successorId(), -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(link.successorId());
                }
            }
        }
        if (order.size() != nodes.size()) {
            throw new IllegalStateException("Dependency graph contains a cycle");
        }
        return order;
    }
}
