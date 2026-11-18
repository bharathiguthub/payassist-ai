package com.payassist;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.payassist.dispute.DisputeCaseWork;
import com.payassist.dispute.DisputeQueries;
import com.payassist.dispute.DisputeResolutions;
import com.payassist.refund.RefundApprovals;
import com.payassist.refund.RefundRequests;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Money-moving decisions stay human-only (ADR 0011, ADR 0012, ADR 0013): refund approval/rejection and dispute
 * resolution may only be reached from HTTP controllers serving a signed-in person, and are implemented in their
 * module's internal package. Any other caller, such as a future AI tool adapter or agent service, fails the build.
 */
class HumanOnlyActionsArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.payassist");

    @Test
    void humanOnlyInterfacesAreUsedOnlyByWebControllersAndTheirImplementations() {
        noClasses()
                .that().resideOutsideOfPackages("com.payassist.refund.web..", "com.payassist.refund.internal..",
                        "com.payassist.dispute.web..", "com.payassist.dispute.internal..")
                .and().doNotBelongToAnyOf(RefundApprovals.class, DisputeResolutions.class)
                .should().dependOnClassesThat().belongToAnyOf(RefundApprovals.class, DisputeResolutions.class)
                .because("approving refunds and resolving disputes are human-only decisions, never tool calls")
                .check(CLASSES);
    }

    @Test
    void toolReadyInterfacesDoNotInheritHumanOnlyOperations() {
        assertThat(RefundApprovals.class.isAssignableFrom(RefundRequests.class)).isFalse();
        assertThat(DisputeResolutions.class.isAssignableFrom(DisputeQueries.class)).isFalse();
        assertThat(DisputeResolutions.class.isAssignableFrom(DisputeCaseWork.class)).isFalse();
    }
}
