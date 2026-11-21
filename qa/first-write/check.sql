-- 격리된 fixture: 72쌍의 독립 요청 + 동일 사용자 요청용 1쌍.
SELECT
  (SELECT COUNT(*) FROM invite_rewards WHERE inviter_user_id BETWEEN 500000 AND 500144 AND inviter_reward_amount = 50 AND invitee_reward_amount = 50) AS reward_pairs,
  (SELECT COUNT(*) FROM wallet_histories WHERE user_id BETWEEN 500000 AND 500145 AND reason = 'INVITE_REWARD' AND amount = 50) AS coin_entries,
  (SELECT COUNT(*) FROM user_wallets WHERE user_id BETWEEN 500000 AND 500145 AND currency_type = 'COIN' AND balance = 150) AS correct_wallets,
  (SELECT COUNT(*) FROM onboarding_house_selections s JOIN house_members m ON s.membership_id = m.id WHERE s.user_id BETWEEN 500000 AND 500144 AND s.result = 'PERSONAL' AND m.user_id = s.user_id AND m.house_id = s.user_id) AS correct_houses,
  (SELECT COUNT(DISTINCT account_token) FROM furniture_credit_accounts WHERE user_id BETWEEN 500000 AND 500144 AND balance = 0 AND reserved = 0) AS distinct_empty_accounts;
-- 같은 초대자의 마지막 두 손님도 초대받은 50코인을 받지만, 초대자 지급은 10건에서 멈춰야 한다.
SELECT COUNT(*), SUM(inviter_reward_amount), SUM(invitee_reward_amount),
       (SELECT balance FROM user_wallets WHERE user_id = 500146 AND currency_type = 'COIN')
FROM invite_rewards WHERE inviter_user_id = 500146;
