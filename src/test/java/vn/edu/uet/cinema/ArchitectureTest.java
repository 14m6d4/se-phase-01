package vn.edu.uet.cinema;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;

/** Enforces ADR 0001: api → business ← persistence, business depends on nothing but the JDK. */
@AnalyzeClasses(packages = "vn.edu.uet.cinema", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule layers = layeredArchitecture()
		.consideringOnlyDependenciesInLayers()
		.layer("Api").definedBy("..cinema.api..")
		.layer("Business").definedBy("..cinema.business..")
		.layer("Persistence").definedBy("..cinema.persistence..")
		.layer("Config").definedBy("..cinema.config..")
		.whereLayer("Api").mayOnlyBeAccessedByLayers("Config")
		.whereLayer("Persistence").mayOnlyBeAccessedByLayers("Config")
		.whereLayer("Business").mayOnlyBeAccessedByLayers("Api", "Persistence", "Config")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule businessIsFrameworkFree = classes()
		.that().resideInAPackage("..cinema.business..")
		.should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..cinema.business..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule inventoryIsSelfContained = classes()
		.that().resideInAPackage("..cinema.business.showtime.inventory..")
		.should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..cinema.business.showtime.inventory..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule inventoryDoesNotDependOnShowtime = noClasses()
		.that().resideInAPackage("..cinema.business.showtime.inventory..")
		.should().dependOnClassesThat().resideInAnyPackage("..cinema.business.showtime")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule showtimeInventoryCycleFree = slices()
		.assignedFrom(new SliceAssignment() {
			@Override
			public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
				String pkg = javaClass.getPackageName();
				if (pkg.equals("vn.edu.uet.cinema.business.showtime")) {
					return SliceIdentifier.of("showtime");
				}
				if (pkg.startsWith("vn.edu.uet.cinema.business.showtime.inventory")) {
					return SliceIdentifier.of("inventory");
				}
				return SliceIdentifier.ignore();
			}

			@Override
			public String getDescription() {
				return "showtime and inventory slices";
			}
		})
		.should().beFreeOfCycles()
		.allowEmptyShould(true);
}
