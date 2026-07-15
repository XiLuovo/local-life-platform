package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.AddressBook;
import com.sky.exception.AddressBookBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.service.AddressBookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Slf4j
public class AddressBookServiceImpl implements AddressBookService {
    private static final String ADDRESS_NOT_FOUND_OR_FORBIDDEN = "地址不存在或无权操作";

    @Autowired
    private AddressBookMapper addressBookMapper;

    /**
     * 条件查询
     *
     * @param addressBook
     * @return
     */
    public List<AddressBook> list(AddressBook addressBook) {
        addressBook.setUserId(BaseContext.getCurrentId());
        return addressBookMapper.list(addressBook);
    }

    /**
     * 新增地址
     *
     * @param addressBook
     */
    public void save(AddressBook addressBook) {
        addressBook.setUserId(BaseContext.getCurrentId());
        addressBook.setIsDefault(0);
        addressBookMapper.insert(addressBook);
    }

    /**
     * 根据id查询
     *
     * @param id
     * @return
     */
    public AddressBook getById(Long id) {
        AddressBook addressBook = addressBookMapper.getByIdAndUserId(id, BaseContext.getCurrentId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(ADDRESS_NOT_FOUND_OR_FORBIDDEN);
        }
        return addressBook;
    }

    /**
     * 根据id修改地址
     *
     * @param addressBook
     */
    public void update(AddressBook addressBook) {
        addressBook.setUserId(BaseContext.getCurrentId());
        if (addressBookMapper.update(addressBook) == 0) {
            throw new AddressBookBusinessException(ADDRESS_NOT_FOUND_OR_FORBIDDEN);
        }
    }

    /**
     * 设置默认地址
     *
     * @param addressBook
     */
    @Transactional
    public void setDefault(AddressBook addressBook) {
        Long userId = BaseContext.getCurrentId();
        if (addressBookMapper.getByIdAndUserId(addressBook.getId(), userId) == null) {
            throw new AddressBookBusinessException(ADDRESS_NOT_FOUND_OR_FORBIDDEN);
        }

        //1、将当前用户的所有地址修改为非默认地址 update address_book set is_default = ? where user_id = ?
        addressBook.setIsDefault(0);
        addressBook.setUserId(userId);
        addressBookMapper.updateIsDefaultByUserId(addressBook);

        //2、将当前地址改为默认地址 update address_book set is_default = ? where id = ?
        addressBook.setIsDefault(1);
        if (addressBookMapper.update(addressBook) == 0) {
            throw new AddressBookBusinessException(ADDRESS_NOT_FOUND_OR_FORBIDDEN);
        }
    }

    /**
     * 根据id删除地址
     *
     * @param id
     */
    public void deleteById(Long id) {
        if (addressBookMapper.deleteById(id, BaseContext.getCurrentId()) == 0) {
            throw new AddressBookBusinessException(ADDRESS_NOT_FOUND_OR_FORBIDDEN);
        }
    }

}
