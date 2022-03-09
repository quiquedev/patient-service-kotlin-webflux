package info.quiquedev.patientservice.patients.usecases

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import info.quiquedev.patientservice.patients.NewPatientDto
import info.quiquedev.patientservice.patients.ReactorUtils.safeMono
import info.quiquedev.patientsservice.patients.usecases.tables.records.PatientsRecord
import info.quiquedev.patientsservice.patients.usecases.tables.references.PATIENTS
import org.jooq.DSLContext
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

class PatientsRepository(
    private val dsl: DSLContext,
    private val clock: Clock
) {
    sealed class PatientsRepositoryError(message: String, throwable: Throwable? = null) : Throwable(message, throwable)
    data class ExistingPassportNumberError(val passportNumber: String) :
        PatientsRepositoryError("existing passport number $passportNumber")

    data class UnexpectedError(val throwable: Throwable) :
        PatientsRepositoryError("unexpected error", throwable)

    data class TooManyPatientsError(val id: String, val amount: Int) :
        PatientsRepositoryError("$amount patients found for id $id")

    fun findPatient(id: String):
            Mono<Option<PatientsRecord>> =
        safeMono {
            val patients = dsl
                .selectFrom(PATIENTS)
                .where(PATIENTS.ID.eq(id))
                .toSet()

            when (patients.size) {
                0 -> none()
                1 -> patients.first().some()
                else -> throw TooManyPatientsError(id, patients.size)
            }
        }.onErrorMap {
            when (it) {
                is TooManyPatientsError -> it
                else -> UnexpectedError(it)
            }
        }


    fun createPatient(
        name: String,
        surname: String,
        passportNumber: String
    ): Mono<PatientsRecord> =
        Mono
            .fromFuture {
                dsl.transactionResultAsync {
                    if (existsPassportNumber(passportNumber))
                        throw ExistingPassportNumberError(passportNumber)
                    storeNewPatient(name, surname, passportNumber)
                }.toCompletableFuture()
            }
            .onErrorMap {
                when (it.cause) {
                    is ExistingPassportNumberError -> it.cause
                    else -> UnexpectedError(it)
                }
            }

    private fun storeNewPatient(
        name: String,
        surname: String,
        passportNumber: String
    ): PatientsRecord {
        val newPatient = dsl.newRecord(PATIENTS)
        val createdAt = Instant.now(clock)

        newPatient.id = UUID.randomUUID().toString()
        newPatient.createdAt = LocalDateTime.ofInstant(createdAt, ZoneId.of("UTC"))
        newPatient.name = name
        newPatient.surname = surname
        newPatient.passportNumber = passportNumber
        newPatient.store()

        return newPatient
    }

    private fun existsPassportNumber(passportNumber: String) =
        dsl.select(PATIENTS.ID)
            .from(PATIENTS)
            .where(PATIENTS.PASSPORT_NUMBER.eq(passportNumber))
            .fetch()
            .toSet()
            .isNotEmpty()
}