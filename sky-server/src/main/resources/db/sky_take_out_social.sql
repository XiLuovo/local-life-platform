CREATE TABLE IF NOT EXISTS `tb_blog` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `shop_id` bigint DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `title` varchar(255) DEFAULT NULL,
  `images` varchar(2048) DEFAULT NULL,
  `content` text,
  `liked` int DEFAULT 0,
  `comments` int DEFAULT 0,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_blog_user` (`user_id`),
  KEY `idx_blog_shop` (`shop_id`)
);

CREATE TABLE IF NOT EXISTS `tb_follow` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `follow_user_id` bigint NOT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_follow_relation` (`user_id`, `follow_user_id`)
);

INSERT IGNORE INTO `tb_blog` (`id`, `shop_id`, `user_id`, `title`, `images`, `content`, `liked`, `comments`) VALUES
  (1, 1, 1, 'Intern Lunch Notes', 'https://example.com/blog1.jpg', 'Found a solid lunch spot near the office.', 3, 1),
  (2, 2, 1, 'Coffee Break Review', 'https://example.com/blog2.jpg', 'Great pour-over and quiet space for interview prep.', 5, 2);
