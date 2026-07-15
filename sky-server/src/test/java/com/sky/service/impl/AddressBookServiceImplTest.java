package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.AddressBook;
import com.sky.exception.AddressBookBusinessException;
import com.sky.mapper.AddressBookMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddressBookServiceImplTest {

    private static final Long CURRENT_USER_ID = 100L;

    @Mock
    private AddressBookMapper addressBookMapper;

    @InjectMocks
    private AddressBookServiceImpl addressBookService;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(CURRENT_USER_ID);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void listAlwaysRestrictsQueryToCurrentUser() {
        AddressBook query = AddressBook.builder().userId(999L).phone("13800000000").build();
        when(addressBookMapper.list(query)).thenReturn(Collections.emptyList());

        addressBookService.list(query);

        assertEquals(CURRENT_USER_ID, query.getUserId());
        verify(addressBookMapper).list(query);
    }

    @Test
    void getByIdRejectsAddressNotOwnedByCurrentUser() {
        when(addressBookMapper.getByIdAndUserId(1L, CURRENT_USER_ID)).thenReturn(null);

        assertThrows(AddressBookBusinessException.class, () -> addressBookService.getById(1L));
    }

    @Test
    void updateForcesCurrentUserAndSucceedsWhenOneRowIsChanged() {
        AddressBook request = AddressBook.builder().id(1L).userId(999L).consignee("张三").build();
        when(addressBookMapper.update(any(AddressBook.class))).thenReturn(1);

        addressBookService.update(request);

        ArgumentCaptor<AddressBook> captor = ArgumentCaptor.forClass(AddressBook.class);
        verify(addressBookMapper).update(captor.capture());
        assertEquals(CURRENT_USER_ID, captor.getValue().getUserId());
    }

    @Test
    void updateRejectsMissingOrForeignAddress() {
        AddressBook request = AddressBook.builder().id(1L).build();
        when(addressBookMapper.update(any(AddressBook.class))).thenReturn(0);

        assertThrows(AddressBookBusinessException.class, () -> addressBookService.update(request));
    }

    @Test
    void deleteRejectsMissingOrForeignAddress() {
        when(addressBookMapper.deleteById(1L, CURRENT_USER_ID)).thenReturn(0);

        assertThrows(AddressBookBusinessException.class, () -> addressBookService.deleteById(1L));
    }

    @Test
    void setDefaultRejectsForeignAddressBeforeChangingExistingDefault() {
        AddressBook request = AddressBook.builder().id(1L).build();
        when(addressBookMapper.getByIdAndUserId(1L, CURRENT_USER_ID)).thenReturn(null);

        assertThrows(AddressBookBusinessException.class, () -> addressBookService.setDefault(request));

        verify(addressBookMapper, never()).updateIsDefaultByUserId(any(AddressBook.class));
        verify(addressBookMapper, never()).update(any(AddressBook.class));
    }

    @Test
    void setDefaultOnlyChangesAddressOwnedByCurrentUser() {
        AddressBook request = AddressBook.builder().id(1L).userId(999L).build();
        when(addressBookMapper.getByIdAndUserId(1L, CURRENT_USER_ID))
                .thenReturn(AddressBook.builder().id(1L).userId(CURRENT_USER_ID).build());
        when(addressBookMapper.update(any(AddressBook.class))).thenReturn(1);

        addressBookService.setDefault(request);

        assertEquals(CURRENT_USER_ID, request.getUserId());
        assertEquals(1, request.getIsDefault());
        verify(addressBookMapper).updateIsDefaultByUserId(any(AddressBook.class));
        verify(addressBookMapper).update(request);
    }
}
