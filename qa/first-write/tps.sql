-- 72명의 독립 첫 요청만 포함한다. 생성권 조회는 최초 계좌 생성 한 건을 센다.
SELECT 'invite', r.inviter_user_id FROM invite_rewards r
JOIN user_wallets a ON a.user_id = r.inviter_user_id AND a.currency_type = 'COIN'
JOIN user_wallets b ON b.user_id = r.invitee_user_id AND b.currency_type = 'COIN'
WHERE r.inviter_user_id BETWEEN 500000 AND 500142 AND r.invitee_user_id = r.inviter_user_id + 1
  AND r.inviter_reward_amount = 50 AND r.invitee_reward_amount = 50 AND a.balance = 150 AND b.balance = 150
UNION ALL
SELECT 'onboarding', s.user_id FROM onboarding_house_selections s
JOIN house_members m ON s.membership_id = m.id AND m.user_id = s.user_id AND m.house_id = s.user_id
WHERE s.user_id BETWEEN 500000 AND 500142 AND s.result = 'PERSONAL'
UNION ALL
SELECT 'credit', user_id FROM furniture_credit_accounts
WHERE user_id BETWEEN 500000 AND 500142 AND balance = 0 AND reserved = 0 AND account_token <> '';
