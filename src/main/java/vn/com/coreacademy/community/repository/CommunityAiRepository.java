package vn.com.coreacademy.community.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.community.entity.AiFeedback;
import vn.com.coreacademy.community.entity.AiTurnSource;
import vn.com.coreacademy.community.entity.CommunityAiTurn;

@Repository
@RequiredArgsConstructor
public class CommunityAiRepository {
    private final CommunityRepository repository;

    public List<CommunityAiTurn> request(String user, UUID request) {
        return repository.list("select t from CommunityAiTurn t where t.userId=:user and t.requestId=:request",
            CommunityAiTurn.class, Map.of("user", user, "request", request));
    }

    public List<AiTurnSource> sources(Long turn) {
        return repository.list("select s from AiTurnSource s where s.turnId=:turn order by s.citationNo",
            AiTurnSource.class, Map.of("turn", turn));
    }

    public List<AiFeedback> feedback(Long turn, String user) {
        return repository.list("select f from AiFeedback f where f.turnId=:turn and f.userId=:user", AiFeedback.class,
            Map.of("turn", turn, "user", user));
    }
}
