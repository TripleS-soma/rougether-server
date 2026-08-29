-- AI 조정 추천 HOLDOUT(#342). 사용자·실험 키마다 CONTROL/TREATMENT를 한 번만 배정하고,
-- 주별 적격 진입을 별도 기록해 추천을 만들지 않는 CONTROL도 효과 측정 분모에 남긴다.
CREATE TABLE recommendation_experiment_assignments (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    experiment_key VARCHAR(80) NOT NULL,
    user_id        BIGINT      NOT NULL,
    variant        VARCHAR(20) NOT NULL,
    created_at     TIMESTAMP   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rec_experiment_assignment_key_user UNIQUE (experiment_key, user_id),
    CONSTRAINT fk_rec_experiment_assignment_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_rec_experiment_assignment_key_variant
    ON recommendation_experiment_assignments (experiment_key, variant);

CREATE TABLE recommendation_experiment_eligibilities (
    id                BIGINT    NOT NULL AUTO_INCREMENT,
    assignment_id     BIGINT    NOT NULL,
    cohort_week_start DATE      NOT NULL,
    created_at        TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rec_experiment_eligibility_assignment_week UNIQUE (assignment_id, cohort_week_start),
    CONSTRAINT fk_rec_experiment_eligibility_assignment
        FOREIGN KEY (assignment_id) REFERENCES recommendation_experiment_assignments (id)
);

CREATE INDEX idx_rec_experiment_eligibility_cohort
    ON recommendation_experiment_eligibilities (cohort_week_start, assignment_id);
