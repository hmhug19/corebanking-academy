package vn.com.coreacademy.learning.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.search.SearchContributor;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;

/** Query contributor Learning tự áp dụng grant/scope và catalog/enrollment; không nhận SQL hoặc scope từ FE. */
@Repository
@RequiredArgsConstructor
public class CommunityCourseSearchContributor implements SearchContributor {
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;

    @Override
    public String sourceType() {
        return "learning";
    }

    @Override
    public Part contribute(Filter filter) {
        var actor = access.actor();
        var scope = access.searchScope(actor);
        var ancestors = identity.activeAncestorIds(actor.person().orgId());
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("learningQuery", filter.q());
        parameters.put("learningUser", actor.person().id());
        parameters.put("learningOrg", actor.person().orgId() == null ? -1L : actor.person().orgId());
        parameters.put("learningGlobal", scope.global());
        parameters.put("learningOrgs", scope.orgIds().isEmpty() ? List.of(-1L) : scope.orgIds());
        parameters.put("learningTeams", scope.teamIds().isEmpty() ? List.of(-1L) : scope.teamIds());
        parameters.put("learningOwn", scope.own());
        parameters.put("learningAncestors", ancestors.isEmpty() ? List.of(-1L) : ancestors);
        parameters.put("learningLearner", actor.grants().stream().anyMatch(grant ->
            RoleCodes.LEARNER.equals(grant.roleCode()) && grant.scope().type() == ScopeType.OWN));
        return new Part(sourceType(), """
            select 'learning'::text source_type, c.course_id source_id, r.revision_id,
              r.title, left(coalesce(r.summary, ''), 400) snippet,
              cast(ts_rank_cd(to_tsvector('simple', r.title || ' ' || coalesce(r.summary, '')),
                plainto_tsquery('simple', :learningQuery), 32) as double precision) score,
              extract(epoch from r.update_time) updated_epoch, '/learning/courses/' || c.course_id url,
              null::bigint answer_id
            from learning_hub.courses c
            left join lateral (select e.revision_id from learning_hub.course_enrollments e
              where e.course_id = c.course_id and e.user_id = :learningUser and e.status <> 'withdrawn'
              order by e.cycle_no desc limit 1) enrolled on true
            join learning_hub.course_revisions r
              on r.revision_id = coalesce(enrolled.revision_id, c.published_revision_id)
            left join learning_hub.training_programs p on p.program_id = r.program_id
            left join learning_hub.training_fields f on f.field_id = p.field_id
            where r.state = 'published' and (
              :learningGlobal = true or c.owner_org_id in (:learningOrgs) or c.owner_team_id in (:learningTeams)
              or (:learningOwn = true and c.owner_user_id = :learningUser and c.owner_org_id = :learningOrg)
              or (:learningLearner = true and (enrolled.revision_id is not null
                or (c.lifecycle_status = 'active' and p.status = 'published' and f.status = 'published'
                  and (c.owner_org_id = :learningOrg or exists (
                    select 1 from learning_hub.course_shares s where s.course_id = c.course_id and s.status = 'active'
                      and (s.org_id = :learningOrg or (s.include_descendants and s.org_id in (:learningAncestors)))))))))
              and (:learningQuery = '' or to_tsvector('simple', r.title || ' ' || coalesce(r.summary, ''))
                @@ plainto_tsquery('simple', :learningQuery)
                or position(lower(:learningQuery) in lower(r.title || ' ' || coalesce(r.summary, ''))) > 0)
            """, parameters);
    }
}
