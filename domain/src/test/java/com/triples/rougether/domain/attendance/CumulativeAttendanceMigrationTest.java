package com.triples.rougether.domain.attendance;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class CumulativeAttendanceMigrationTest {
    @Test void V100은_기존지급제약을보존하고_누적코인설정과계정별숨김을지원한다() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:attendance-migration;MODE=MySQL");
                var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE items(id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE user_items(id BIGINT PRIMARY KEY)");
            for(String version:new String[]{"V47__add_continuous_attendance_events.sql",
                    "V68__add_attendance_generation_credit_reward.sql",
                    "V100__add_cumulative_attendance_and_popup_dismissals.sql"}) {
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+version));
            }
            sql.execute("INSERT INTO users VALUES (1),(2)");
            sql.execute("INSERT INTO items VALUES (1)");
            sql.execute("INSERT INTO user_items VALUES (1)");
            sql.execute("INSERT INTO attendance_events(id,code,title,starts_on,ends_on,target_days,daily_coin_amount,bonus_day,bonus_coin_amount,reward_item_id,is_active,created_at) VALUES (1,'legacy','기존','2026-11-09','2026-12-25',7,30,5,50,1,true,CURRENT_TIMESTAMP)");
            try(var row=sql.executeQuery("SELECT mode,milestone_every FROM attendance_events WHERE id=1")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("STREAK");
                assertThat(row.getInt(2)).isZero();
            }
            assertThatThrownBy(()->sql.execute("UPDATE attendance_events SET reward_item_id=NULL WHERE id=1"))
                    .isInstanceOf(SQLException.class);
            sql.execute("INSERT INTO attendance_events(id,code,title,starts_on,ends_on,target_days,daily_coin_amount,bonus_day,bonus_coin_amount,is_active,created_at,mode,milestone_every,milestone_coin_amount) VALUES (2,'christmas_2026','크리스마스','2026-11-09','2026-12-25',30,30,30,30,true,CURRENT_TIMESTAMP,'CUMULATIVE',5,60)");
            sql.execute("INSERT INTO attendance_check_ins(event_id,user_id,attendance_date,streak_day,coin_reward_amount,created_at,progress_completed_at) VALUES(2,1,'2026-12-08',30,60,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            assertThatThrownBy(()->sql.execute("UPDATE attendance_check_ins SET reward_processed_at=CURRENT_TIMESTAMP"))
                    .isInstanceOf(SQLException.class);
            sql.execute("INSERT INTO event_popup_dismissals(user_id,event_code,dismissed_at) VALUES(1,'christmas_2026',CURRENT_TIMESTAMP),(2,'christmas_2026',CURRENT_TIMESTAMP)");
            assertThatThrownBy(()->sql.execute("INSERT INTO event_popup_dismissals(user_id,event_code,dismissed_at) VALUES(1,'christmas_2026',CURRENT_TIMESTAMP)"))
                    .isInstanceOf(SQLException.class);
            sql.execute("DELETE FROM users WHERE id=2");
            try(var row=sql.executeQuery("SELECT COUNT(*) FROM event_popup_dismissals")) {
                assertThat(row.next()).isTrue();assertThat(row.getInt(1)).isEqualTo(1);
            }
        }
    }
}
