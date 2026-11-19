package cn.jualn.miniapp.module.person.service;

import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;

import cn.jualn.miniapp.module.person.entity.ManagedPersonRows;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonDeliveryBindingTest {
    final ManagedPersonMapper persons = mock(ManagedPersonMapper.class);
    final PersonBusinessScopes scopes = new PersonBusinessScopes(null,mock(PersonMapper.class),persons,null);

    @Test void unavailablePersonNeverSuppliesAnAccountEvenWhenOldBindingRemains() {
        var profile = new ManagedPersonRows.Profile();profile.setStatus("INACTIVE");
        when(persons.profile("person")).thenReturn(profile);
        assertNull(scopes.deliveryBinding("person").userId());
        verify(persons,never()).personBinding(anyString());
    }

    @Test void deliveryFollowsCurrentBindingWithoutChangingPersonIdentity() {
        var profile = new ManagedPersonRows.Profile();profile.setStatus("ACTIVE");
        var first = new ManagedPersonRows.Binding();first.setUserId(7L);first.setId("old");
        var second = new ManagedPersonRows.Binding();second.setUserId(8L);second.setId("new");
        when(persons.profile("person")).thenReturn(profile);
        when(persons.personBinding("person")).thenReturn(first,second,null);
        assertEquals(new PersonBusinessScopes.DeliveryBinding("ACTIVE",7L,"old"),scopes.deliveryBinding("person"));
        assertEquals(new PersonBusinessScopes.DeliveryBinding("ACTIVE",8L,"new"),scopes.deliveryBinding("person"));
        assertNull(scopes.deliveryBinding("person").userId());
    }
}
