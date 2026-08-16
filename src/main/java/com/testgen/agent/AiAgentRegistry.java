package com.testgen.agent;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent rolü ile implementasyonu arasındaki tek, fail-fast kayıt noktası.
 *
 * <p>Eksik agent'ın ancak uzun süren bir üretimin ortasında fark edilmesini ve aynı
 * role sahip iki bean'den hangisinin çalışacağının Spring liste sırasına bağlı kalmasını
 * engeller. Yeni bir {@link AiAgentRole} eklendiğinde implementasyonu yoksa uygulama
 * başlangıçta açık hatayla durur.
 */
@Component
public class AiAgentRegistry {

    private final Map<AiAgentRole, AiAgent> agents;

    public AiAgentRegistry(List<AiAgent> discoveredAgents) {
        Objects.requireNonNull(discoveredAgents, "discoveredAgents");

        EnumMap<AiAgentRole, AiAgent> registered = new EnumMap<>(AiAgentRole.class);
        for (AiAgent agent : discoveredAgents) {
            if (agent == null || agent.role() == null) {
                throw new IllegalStateException("Agent kaydı ve rolü null olamaz.");
            }
            AiAgent previous = registered.putIfAbsent(agent.role(), agent);
            if (previous != null) {
                throw new IllegalStateException("Aynı rol için birden fazla agent kayıtlı: "
                        + agent.role());
            }
        }

        EnumSet<AiAgentRole> missing = EnumSet.allOf(AiAgentRole.class);
        missing.removeAll(registered.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Agent kaydı eksik roller: " + missing);
        }

        this.agents = Map.copyOf(registered);
    }

    public AiAgent required(AiAgentRole role) {
        return agents.get(Objects.requireNonNull(role, "role"));
    }

    /**
     * Orkestrasyon katmanının bir rolü planlamadan önce doğrulaması için açık
     * kullanılabilirlik sorgusu. Registry uygulama başlangıcında zaten fail-fast
     * davrandığından normal üretim akışında tüm roller kayıtlıdır; bu metot yeni
     * planlama sınırının somut agent sınıflarını bilmesine gerek bırakmaz.
     */
    public boolean contains(AiAgentRole role) {
        return agents.containsKey(Objects.requireNonNull(role, "role"));
    }
}
