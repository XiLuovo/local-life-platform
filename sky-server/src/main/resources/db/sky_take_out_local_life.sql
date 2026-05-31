CREATE TABLE IF NOT EXISTS `tb_shop_type` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(32) NOT NULL,
  `icon` varchar(255) DEFAULT NULL,
  `sort` int DEFAULT 0,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `tb_shop` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL,
  `type_id` bigint NOT NULL,
  `images` varchar(2048) DEFAULT NULL,
  `area` varchar(128) DEFAULT NULL,
  `address` varchar(255) DEFAULT NULL,
  `x` double DEFAULT NULL,
  `y` double DEFAULT NULL,
  `avg_price` bigint DEFAULT 0,
  `sold` int DEFAULT 0,
  `comments` int DEFAULT 0,
  `score` int DEFAULT 0,
  `open_hours` varchar(255) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_shop_type` (`type_id`)
);

CREATE TABLE IF NOT EXISTS `tb_voucher` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `shop_id` bigint NOT NULL,
  `title` varchar(255) NOT NULL,
  `sub_title` varchar(255) DEFAULT NULL,
  `rules` varchar(2048) DEFAULT NULL,
  `pay_value` bigint NOT NULL,
  `actual_value` bigint NOT NULL,
  `type` tinyint NOT NULL DEFAULT 0,
  `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_voucher_shop` (`shop_id`)
);

CREATE TABLE IF NOT EXISTS `tb_seckill_voucher` (
  `voucher_id` bigint NOT NULL,
  `stock` int NOT NULL DEFAULT 0,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `begin_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`voucher_id`)
);

CREATE TABLE IF NOT EXISTS `tb_voucher_order` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `voucher_id` bigint NOT NULL,
  `pay_type` tinyint DEFAULT 3,
  `status` tinyint DEFAULT 2,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `pay_time` datetime DEFAULT NULL,
  `use_time` datetime DEFAULT NULL,
  `refund_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voucher_user` (`user_id`, `voucher_id`),
  KEY `idx_voucher_order_voucher` (`voucher_id`)
);

INSERT IGNORE INTO `tb_shop_type` (`id`, `name`, `icon`, `sort`) VALUES
  (1, 'Food', '/types/food.png', 1),
  (2, 'Cafe', '/types/cafe.png', 2);

INSERT IGNORE INTO `tb_shop` (`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `avg_price`, `sold`, `comments`, `score`, `open_hours`) VALUES
  (1, 'Campus Noodle House', 1, 'https://example.com/noodle.jpg', 'University Town', '1 Internship Road', 120.149192, 30.316078, 38, 1250, 320, 46, '10:00-21:00'),
  (2, 'Offer Coffee Lab', 2, 'https://example.com/coffee.jpg', 'Innovation Park', '99 Future Avenue', 120.151505, 30.333422, 42, 860, 210, 48, '08:00-20:00');

INSERT IGNORE INTO `tb_voucher` (`id`, `shop_id`, `title`, `sub_title`, `rules`, `pay_value`, `actual_value`, `type`, `status`) VALUES
  (1, 1, '50 Off 100', 'All week available', 'Dine-in only', 10000, 5000, 0, 1),
  (2, 2, 'Flash Coffee Coupon', 'Limited seckill offer', 'One per user', 3000, 990, 1, 1);

INSERT IGNORE INTO `tb_seckill_voucher` (`voucher_id`, `stock`, `begin_time`, `end_time`) VALUES
  (2, 50, '2026-01-01 00:00:00', '2026-12-31 23:59:59');
