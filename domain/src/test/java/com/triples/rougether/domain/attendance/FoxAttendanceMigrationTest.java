package com.triples.rougether.domain.attendance;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class FoxAttendanceMigrationTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void V102는_기존보상과선등록여우를보존하고_누적3일차전용보상을설정한다(boolean preexisting) throws Exception {
        try(var conn=DriverManager.getConnection("jdbc:h2:mem:fox-"+UUID.randomUUID()+";MODE=MySQL");var sql=conn.createStatement()){
            sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE items(id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE user_items(id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE characters(id BIGINT AUTO_INCREMENT PRIMARY KEY,code VARCHAR(50) UNIQUE,name VARCHAR(100),name_translations VARCHAR(255),base_asset_key VARCHAR(255),sort_order INT,is_active BOOLEAN)");
            sql.execute("CREATE TABLE character_poses(id BIGINT AUTO_INCREMENT PRIMARY KEY,character_id BIGINT,code VARCHAR(40),asset_key VARCHAR(255),sort_order INT,is_active BOOLEAN,created_at TIMESTAMP,updated_at TIMESTAMP)");
            for(String v:new String[]{"V47__add_continuous_attendance_events.sql","V68__add_attendance_generation_credit_reward.sql","V100__add_cumulative_attendance_and_popup_dismissals.sql"})
                ScriptUtils.executeSqlScript(conn,new ClassPathResource("db/migration/"+v));
            sql.execute("INSERT INTO items VALUES(1)");
            sql.execute("INSERT INTO characters(code,name,base_asset_key,sort_order,is_active) VALUES('cat','고양이','characters/cat.png',1,TRUE)");
            if(preexisting)sql.execute("INSERT INTO characters(code,name,base_asset_key,sort_order,is_active) VALUES('fox','여우','characters/fox/base-v1.webp',90,FALSE)");
            sql.execute("INSERT INTO attendance_events(id,code,title,starts_on,ends_on,target_days,daily_coin_amount,bonus_day,bonus_coin_amount,reward_item_id,is_active,created_at) VALUES(1,'legacy','기존','2026-11-09','2026-12-25',7,30,5,50,1,TRUE,CURRENT_TIMESTAMP)");
            sql.execute("INSERT INTO attendance_events(id,code,title,starts_on,ends_on,target_days,daily_coin_amount,bonus_day,bonus_coin_amount,is_active,created_at,mode,milestone_every,milestone_coin_amount) VALUES(2,'christmas_2026','크리스마스','2026-11-09','2026-12-25',30,30,30,30,TRUE,CURRENT_TIMESTAMP,'CUMULATIVE',5,60)");
            ScriptUtils.executeSqlScript(conn,new ClassPathResource("db/migration/V102__christmas_attendance_fox_reward.sql"));
            try(var r=sql.executeQuery("SELECT e.character_reward_day,c.code,c.is_attendance_reward,c.is_active,e.daily_coin_amount,e.milestone_coin_amount,e.target_days FROM attendance_events e JOIN characters c ON c.id=e.reward_character_id WHERE e.id=2")){
                assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(3);assertThat(r.getString(2)).isEqualTo("fox");
                assertThat(r.getBoolean(3)).isTrue();assertThat(r.getBoolean(4)).isFalse();
                assertThat(r.getInt(5)).isEqualTo(30);assertThat(r.getInt(6)).isEqualTo(60);assertThat(r.getInt(7)).isEqualTo(30);
            }
            try(var r=sql.executeQuery("SELECT reward_item_id,character_reward_day,reward_character_id FROM attendance_events WHERE id=1")){
                assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(1);assertThat(r.getInt(2)).isZero();assertThat(r.getObject(3)).isNull();
            }
            try(var r=sql.executeQuery("SELECT COUNT(*) FROM character_poses cp JOIN characters c ON c.id=cp.character_id WHERE c.code='fox'")){
                assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(3);
            }
            assertThatThrownBy(()->sql.execute("UPDATE attendance_events SET character_reward_day=0 WHERE id=2")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.execute("UPDATE attendance_events SET character_reward_day=31 WHERE id=2")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.execute("UPDATE attendance_events SET reward_character_id=999999 WHERE id=2")).isInstanceOf(SQLException.class);
        }
    }
}
