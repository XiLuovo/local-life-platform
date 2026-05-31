# API Overview

## User Authentication

- `POST /user/user/code`
- `POST /user/user/login`
- `GET /user/user/me`
- `GET /user/user/{id}`
- `POST /user/user/sign`
- `GET /user/user/sign/count`

## Store and Voucher

- `GET /user/store-type/list`
- `GET /user/store/{id}`
- `GET /user/store/of/type`
- `GET /user/store/of/name`
- `GET /user/voucher/list/{shopId}`
- `POST /user/voucher-order/seckill/{id}`

## Social

- `POST /user/blog`
- `PUT /user/blog/like/{id}`
- `GET /user/blog/of/me`
- `GET /user/blog/hot`
- `GET /user/blog/{id}`
- `GET /user/blog/likes/{id}`
- `GET /user/blog/of/user`
- `GET /user/blog/of/follow`
- `PUT /user/follow/{id}/{isFollow}`
- `GET /user/follow/or/not/{id}`
- `GET /user/follow/common/{id}`

## Take-out User Flow

- `GET /user/addressBook/list`
- `POST /user/addressBook`
- `GET /user/addressBook/{id}`
- `PUT /user/addressBook`
- `PUT /user/addressBook/default`
- `DELETE /user/addressBook`
- `GET /user/addressBook/default`
- `GET /user/category/list`
- `GET /user/dish/list`
- `GET /user/setmeal/list`
- `GET /user/setmeal/dish/{id}`
- `POST /user/shoppingCart/add`
- `POST /user/shoppingCart/sub`
- `GET /user/shoppingCart/list`
- `DELETE /user/shoppingCart/clean`
- `POST /user/order/submit`
- `PUT /user/order/payment`
- `GET /user/order/historyOrders`
- `GET /user/order/orderDetail/{id}`
- `PUT /user/order/cancel/{id}`
- `POST /user/order/repetition/{id}`
- `GET /user/order/reminder/{id}`

## Admin

- `POST /admin/employee/login`
- `POST /admin/employee/logout`
- `POST /admin/employee`
- `GET /admin/employee/page`
- `POST /admin/employee/status/{status}`
- `GET /admin/employee/{id}`
- `PUT /admin/employee`
- `POST /admin/category`
- `GET /admin/category/page`
- `DELETE /admin/category`
- `PUT /admin/category`
- `POST /admin/category/status/{status}`
- `GET /admin/category/list`
- `POST /admin/dish`
- `GET /admin/dish/page`
- `DELETE /admin/dish`
- `GET /admin/dish/{id}`
- `PUT /admin/dish`
- `POST /admin/dish/status/{status}`
- `GET /admin/dish/list`
- `POST /admin/setmeal`
- `GET /admin/setmeal/page`
- `DELETE /admin/setmeal`
- `GET /admin/setmeal/{id}`
- `PUT /admin/setmeal`
- `POST /admin/setmeal/status/{status}`
- `PUT /admin/shop/{status}`
- `GET /admin/shop/status`
- `POST /admin/voucher`
- `POST /admin/voucher/seckill`
- `GET /admin/order/conditionSearch`
- `GET /admin/order/statistics`
- `GET /admin/order/details/{id}`
- `PUT /admin/order/confirm`
- `PUT /admin/order/rejection`
- `PUT /admin/order/cancel`
- `PUT /admin/order/delivery/{id}`
- `PUT /admin/order/complete/{id}`
- `GET /admin/workspace/businessData`
- `GET /admin/workspace/overviewOrders`
- `GET /admin/workspace/overviewDishes`
- `GET /admin/workspace/overviewSetmeals`
- `GET /admin/report/turnoverStatistics`
- `GET /admin/report/userStatistics`
- `GET /admin/report/ordersStatistics`
- `GET /admin/report/top10`
- `GET /admin/report/export`
