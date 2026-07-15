SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `employee` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(32) NOT NULL,
  `username` varchar(32) NOT NULL,
  `password` varchar(64) NOT NULL,
  `phone` varchar(11) NOT NULL,
  `sex` varchar(2) NOT NULL,
  `id_number` varchar(18) NOT NULL,
  `status` int NOT NULL DEFAULT 1,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_user` bigint DEFAULT NULL,
  `update_user` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_employee_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `openid` varchar(64) DEFAULT NULL,
  `name` varchar(32) DEFAULT NULL,
  `phone` varchar(11) DEFAULT NULL,
  `sex` varchar(2) DEFAULT NULL,
  `id_number` varchar(18) DEFAULT NULL,
  `avatar` varchar(500) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_phone` (`phone`),
  UNIQUE KEY `uk_user_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `address_book` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `consignee` varchar(50) DEFAULT NULL,
  `sex` varchar(2) DEFAULT NULL,
  `phone` varchar(11) NOT NULL,
  `province_code` varchar(12) DEFAULT NULL,
  `province_name` varchar(32) DEFAULT NULL,
  `city_code` varchar(12) DEFAULT NULL,
  `city_name` varchar(32) DEFAULT NULL,
  `district_code` varchar(12) DEFAULT NULL,
  `district_name` varchar(32) DEFAULT NULL,
  `detail` varchar(200) DEFAULT NULL,
  `label` varchar(100) DEFAULT NULL,
  `is_default` tinyint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_address_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `type` int DEFAULT NULL,
  `name` varchar(32) NOT NULL,
  `sort` int NOT NULL DEFAULT 0,
  `status` int DEFAULT 1,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_user` bigint DEFAULT NULL,
  `update_user` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_category_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `dish` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(32) NOT NULL,
  `category_id` bigint NOT NULL,
  `price` decimal(10,2) NOT NULL,
  `image` varchar(255) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `status` int DEFAULT 1,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_user` bigint DEFAULT NULL,
  `update_user` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dish_name` (`name`),
  KEY `idx_dish_category` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `dish_flavor` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `dish_id` bigint NOT NULL,
  `name` varchar(32) DEFAULT NULL,
  `value` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_flavor_dish` (`dish_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `setmeal` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category_id` bigint NOT NULL,
  `name` varchar(32) NOT NULL,
  `price` decimal(10,2) NOT NULL,
  `status` int DEFAULT 1,
  `description` varchar(255) DEFAULT NULL,
  `image` varchar(255) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_user` bigint DEFAULT NULL,
  `update_user` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_setmeal_name` (`name`),
  KEY `idx_setmeal_category` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `setmeal_dish` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `setmeal_id` bigint DEFAULT NULL,
  `dish_id` bigint DEFAULT NULL,
  `name` varchar(32) DEFAULT NULL,
  `price` decimal(10,2) DEFAULT NULL,
  `copies` int DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_setmeal_dish_setmeal` (`setmeal_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `shopping_cart` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(32) DEFAULT NULL,
  `image` varchar(255) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `dish_id` bigint DEFAULT NULL,
  `setmeal_id` bigint DEFAULT NULL,
  `dish_flavor` varchar(50) DEFAULT NULL,
  `number` int NOT NULL DEFAULT 1,
  `amount` decimal(10,2) NOT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_cart_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `number` varchar(50) NOT NULL,
  `status` int NOT NULL DEFAULT 1,
  `user_id` bigint NOT NULL,
  `address_book_id` bigint NOT NULL,
  `order_time` datetime NOT NULL,
  `checkout_time` datetime DEFAULT NULL,
  `pay_method` int NOT NULL DEFAULT 1,
  `pay_status` tinyint NOT NULL DEFAULT 0,
  `amount` decimal(10,2) NOT NULL,
  `remark` varchar(100) DEFAULT NULL,
  `phone` varchar(11) DEFAULT NULL,
  `address` varchar(255) DEFAULT NULL,
  `user_name` varchar(32) DEFAULT NULL,
  `consignee` varchar(32) DEFAULT NULL,
  `cancel_reason` varchar(255) DEFAULT NULL,
  `rejection_reason` varchar(255) DEFAULT NULL,
  `cancel_time` datetime DEFAULT NULL,
  `estimated_delivery_time` datetime DEFAULT NULL,
  `delivery_status` tinyint NOT NULL DEFAULT 1,
  `delivery_time` datetime DEFAULT NULL,
  `pack_amount` int DEFAULT 0,
  `tableware_number` int DEFAULT 0,
  `tableware_status` tinyint NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_orders_number` (`number`),
  KEY `idx_orders_user_time` (`user_id`,`order_time`),
  KEY `idx_orders_status_time` (`status`,`order_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `order_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(32) DEFAULT NULL,
  `image` varchar(255) DEFAULT NULL,
  `order_id` bigint NOT NULL,
  `dish_id` bigint DEFAULT NULL,
  `setmeal_id` bigint DEFAULT NULL,
  `dish_flavor` varchar(50) DEFAULT NULL,
  `number` int NOT NULL DEFAULT 1,
  `amount` decimal(10,2) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_order_detail_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `employee`
  (`id`,`name`,`username`,`password`,`phone`,`sex`,`id_number`,`status`,`create_user`,`update_user`)
VALUES
  (1,'管理员','admin','e10adc3949ba59abbe56e057f20f883e','13800000000','1','110101199001010000',1,1,1);

INSERT IGNORE INTO `user` (`id`,`name`,`phone`,`avatar`) VALUES
  (1,'演示用户','13800138000','');

INSERT IGNORE INTO `address_book`
  (`id`,`user_id`,`consignee`,`sex`,`phone`,`province_name`,`city_name`,`district_name`,`detail`,`label`,`is_default`)
VALUES
  (1,1,'演示用户','1','13800138000','北京市','北京市','海淀区','上地十街10号','公司',1);

INSERT IGNORE INTO `category` (`id`,`type`,`name`,`sort`,`status`,`create_user`,`update_user`) VALUES
  (1,1,'主食',1,1,1,1),
  (2,1,'饮品',2,1,1,1),
  (3,2,'推荐套餐',3,1,1,1);

INSERT IGNORE INTO `dish`
  (`id`,`name`,`category_id`,`price`,`image`,`description`,`status`,`create_user`,`update_user`)
VALUES
  (1,'番茄鸡蛋面',1,18.00,'https://example.com/dish-noodle.jpg','本地演示菜品',1,1,1),
  (2,'米饭',1,2.00,'https://example.com/dish-rice.jpg','本地演示菜品',1,1,1),
  (3,'柠檬水',2,6.00,'https://example.com/dish-drink.jpg','本地演示饮品',1,1,1);

INSERT IGNORE INTO `dish_flavor` (`id`,`dish_id`,`name`,`value`) VALUES
  (1,1,'口味','["不辣","微辣","中辣"]'),
  (2,3,'温度','["常温","少冰","去冰"]');

INSERT IGNORE INTO `setmeal`
  (`id`,`category_id`,`name`,`price`,`status`,`description`,`image`,`create_user`,`update_user`)
VALUES
  (1,3,'实习生午餐套餐',24.00,1,'番茄鸡蛋面加柠檬水','https://example.com/setmeal.jpg',1,1);

INSERT IGNORE INTO `setmeal_dish` (`setmeal_id`,`dish_id`,`name`,`price`,`copies`) VALUES
  (1,1,'番茄鸡蛋面',18.00,1),
  (1,3,'柠檬水',6.00,1);
