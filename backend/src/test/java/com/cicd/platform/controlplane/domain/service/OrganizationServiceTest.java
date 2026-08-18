package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.api.exception.ResourceConflictException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Organization;
import com.cicd.platform.controlplane.domain.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private OrganizationService organizationService;

    private Organization testOrg;

    @BeforeEach
    void setUp() {
        testOrg = new Organization("Test Org", "test-org", "A test organization");
    }

    @Test
    void createShouldPersistOrganization() {
        when(organizationRepository.existsBySlug("test-org")).thenReturn(false);
        when(organizationRepository.save(any(Organization.class))).thenReturn(testOrg);

        Organization result = organizationService.create("Test Org", "test-org", "A test organization");

        assertNotNull(result);
        assertEquals("Test Org", result.getName());
        assertEquals("test-org", result.getSlug());
        verify(organizationRepository).save(any(Organization.class));
    }

    @Test
    void createShouldThrowOnDuplicateSlug() {
        when(organizationRepository.existsBySlug("test-org")).thenReturn(true);

        assertThrows(ResourceConflictException.class, () ->
                organizationService.create("Test Org", "test-org", "desc"));
    }

    @Test
    void findByIdShouldReturnExisting() {
        when(organizationRepository.findById(any(UUID.class))).thenReturn(Optional.of(testOrg));

        Organization result = organizationService.findById(UUID.randomUUID());

        assertNotNull(result);
        assertEquals("Test Org", result.getName());
    }

    @Test
    void findByIdShouldThrowWhenNotFound() {
        when(organizationRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                organizationService.findById(UUID.randomUUID()));
    }
}
