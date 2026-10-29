import type {
  AllergyIntolerance,
  Appointment,
  Condition,
  Encounter,
  Observation,
  Patient,
  Practitioner,
  PractitionerRole,
  Slot,
} from '@medplum/fhirtypes';
import dayjs, { type Dayjs } from 'dayjs';
import { SPECIALTIES, systemUrl } from '@/api/terminology';

/**
 * Dữ liệu GIẢ cho chế độ mock và test, theo docs/00-synthetic-data.md:
 * tên tiếng Việt tự đặt, số điện thoại dải dành riêng 09000xxxxx, mã BN tiền tố BN-2026, không phải người thật.
 * Tạo lại mỗi lần gọi createDb() theo ngày hiện tại, để lịch luôn nằm quanh "hôm nay".
 */
export interface MockDb {
  practitioners: Practitioner[];
  roles: PractitionerRole[];
  patients: Patient[];
  slots: Slot[];
  appointments: Appointment[];
  encounters: Encounter[];
  conditions: Condition[];
  allergies: AllergyIntolerance[];
  observations: Observation[];
}

const DOCTORS: [string, string, string, string][] = [
  // id, họ, tên, chuyên khoa
  ['pr-001', 'Lê', 'Minh Quân', 'cardiology'],
  ['pr-002', 'Phạm', 'Thu Trang', 'cardiology'],
  ['pr-003', 'Đỗ', 'Văn Hải', 'internal-medicine'],
  ['pr-004', 'Bùi', 'Ngọc Anh', 'internal-medicine'],
  ['pr-005', 'Hoàng', 'Gia Bảo', 'pediatrics'],
  ['pr-006', 'Vũ', 'Thanh Hương', 'obstetrics-gynecology'],
  ['pr-007', 'Đặng', 'Quốc Việt', 'dermatology'],
  ['pr-008', 'Ngô', 'Mai Phương', 'ent'],
  ['pr-009', 'Trịnh', 'Đức Long', 'ophthalmology'],
  ['pr-010', 'Lý', 'Hoài Nam', 'orthopedics'],
  ['pr-011', 'Phan', 'Bích Ngọc', 'neurology'],
  ['pr-012', 'Tạ', 'Công Minh', 'gastroenterology'],
];

const PATIENTS: [string, string, string, 'male' | 'female', string][] = [
  // id, họ, tên đệm + tên, giới, ngày sinh
  ['p-001', 'Nguyễn', 'Thị Lan', 'female', '1988-04-12'],
  ['p-002', 'Trần', 'Văn Bình', 'male', '1956-09-03'],
  ['p-003', 'Lê', 'Hoàng Yến', 'female', '2015-01-27'],
  ['p-004', 'Phạm', 'Quang Dũng', 'male', '1972-11-30'],
  ['p-005', 'Hoàng', 'Thị Mai', 'female', '1994-06-18'],
  ['p-006', 'Vũ', 'Minh Khang', 'male', '2001-02-08'],
  ['p-007', 'Đặng', 'Thu Thảo', 'female', '1979-12-21'],
  ['p-008', 'Bùi', 'Anh Tuấn', 'male', '1965-03-14'],
];

const SLOT_TIMES = [
  '08:00',
  '08:30',
  '09:00',
  '09:30',
  '10:00',
  '10:30',
  '11:00',
  '13:30',
  '14:00',
  '14:30',
  '15:00',
  '15:30',
  '16:00',
];

function at(day: Dayjs, hhmm: string): Dayjs {
  const [h, m] = hhmm.split(':').map(Number);
  return day
    .hour(h ?? 0)
    .minute(m ?? 0)
    .second(0)
    .millisecond(0);
}

/** Số giả ổn định theo chuỗi (để dữ liệu giống nhau giữa các lần tạo). */
function hash(s: string): number {
  let h = 2166136261;
  for (const c of s) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
  return Math.abs(h);
}

export function patientRef(id: string): { reference: string; display: string } {
  const p = PATIENTS.find((x) => x[0] === id);
  return { reference: `Patient/${id}`, display: p ? `${p[1]} ${p[2]}` : id };
}

export function practitionerRef(id: string): { reference: string; display: string } {
  const d = DOCTORS.find((x) => x[0] === id);
  return { reference: `Practitioner/${id}`, display: d ? `BS. ${d[1]} ${d[2]}` : id };
}

export function createDb(now: Dayjs = dayjs()): MockDb {
  const specialtySystem = systemUrl('specialty');
  const practitioners: Practitioner[] = DOCTORS.map(([id, family, given]) => ({
    resourceType: 'Practitioner',
    id,
    active: true,
    name: [{ family, given: [given], prefix: ['BS.'] }],
  }));
  const roles: PractitionerRole[] = DOCTORS.map(([id, , , specialty]) => ({
    resourceType: 'PractitionerRole',
    id: `role-${id}`,
    active: true,
    practitioner: { reference: `Practitioner/${id}` },
    specialty: [
      {
        coding: [
          {
            system: specialtySystem,
            code: specialty,
            display: SPECIALTIES.find((s) => s.code === specialty)?.display,
          },
        ],
      },
    ],
  }));

  const patients: Patient[] = PATIENTS.map(([id, family, given, gender, birthDate], i) => ({
    resourceType: 'Patient',
    id,
    active: true,
    meta: { versionId: '1' },
    identifier: [{ system: 'urn:shp:mrn', value: `BN-2026-${String(i + 1).padStart(6, '0')}` }],
    name: [{ family, given: [given] }],
    gender,
    birthDate,
    telecom: [{ system: 'phone', value: `09000${String(10001 + i * 37).padStart(5, '0')}` }],
  }));

  // Slot: từ thứ Hai tuần này đến 21 ngày sau, ngày làm việc (T2–T6), mỗi bác sĩ 13 slot/ngày.
  const slots: Slot[] = [];
  const monday = now.startOf('day').subtract((now.day() + 6) % 7, 'day');
  for (let d = 0; d < 28; d++) {
    const day = monday.add(d, 'day');
    if (day.day() === 0 || day.day() === 6) continue;
    for (const [pid, , , specialty] of DOCTORS) {
      for (const t of SLOT_TIMES) {
        const start = at(day, t);
        const id = `s-${pid}-${start.format('YYYYMMDDHHmm')}`;
        const r = hash(id) % 10;
        slots.push({
          resourceType: 'Slot',
          id,
          schedule: { reference: `Schedule/sch-${pid}`, display: practitionerRef(pid).display },
          serviceType: [{ coding: [{ system: specialtySystem, code: specialty }] }],
          status: r === 0 ? 'busy-unavailable' : r <= 2 ? 'busy' : 'free',
          start: start.format(),
          end: start.add(30, 'minute').format(),
        });
      }
    }
  }

  const slotOf = (pid: string, start: Dayjs) => slots.find((s) => s.id === `s-${pid}-${start.format('YYYYMMDDHHmm')}`);
  const appointments: Appointment[] = [];
  function addAppt(
    id: string,
    patientId: string,
    pid: string,
    start: Dayjs,
    status: Appointment['status'],
    reason?: string,
  ) {
    const slot = slotOf(pid, start);
    if (slot) slot.status = 'busy';
    appointments.push({
      resourceType: 'Appointment',
      id,
      meta: { versionId: '1' },
      status,
      start: start.format(),
      end: start.add(30, 'minute').format(),
      slot: slot ? [{ reference: `Slot/${slot.id}` }] : undefined,
      serviceType: [{ coding: [{ system: specialtySystem, code: DOCTORS.find((d) => d[0] === pid)?.[3] }] }],
      description: reason,
      participant: [
        { actor: patientRef(patientId), status: 'accepted' },
        { actor: practitionerRef(pid), status: 'accepted' },
      ],
    });
  }

  // Ngày làm việc gần nhất kể từ hôm nay (cuối tuần thì lùi về thứ Sáu để hàng đợi có dữ liệu).
  let workday = now.startOf('day');
  while (workday.day() === 0 || workday.day() === 6) workday = workday.subtract(1, 'day');
  const nextWorkday = (n: number) => {
    let d = now.startOf('day');
    let k = 0;
    while (k < n) {
      d = d.add(1, 'day');
      if (d.day() !== 0 && d.day() !== 6) k++;
    }
    return d;
  };

  // Bệnh nhân p-001: 1 lịch sắp tới, 2 lịch đã qua.
  addAppt('a-001', 'p-001', 'pr-001', at(nextWorkday(2), '09:00'), 'booked', 'Đau ngực khi gắng sức');
  addAppt('a-002', 'p-001', 'pr-003', at(now.subtract(20, 'day'), '10:00'), 'fulfilled', 'Khám sức khỏe định kỳ');
  addAppt('a-003', 'p-001', 'pr-007', at(now.subtract(45, 'day'), '14:00'), 'cancelled', 'Ngứa da');

  // Hàng đợi hôm nay của BS. Lê Minh Quân (pr-001) và vài bác sĩ khác.
  const queue: [string, string, string, Appointment['status']][] = [
    ['p-002', 'pr-001', '08:00', 'fulfilled'],
    ['p-004', 'pr-001', '08:30', 'arrived'],
    ['p-005', 'pr-001', '09:30', 'booked'],
    ['p-006', 'pr-001', '10:30', 'booked'],
    ['p-007', 'pr-001', '14:00', 'booked'],
    ['p-008', 'pr-003', '09:00', 'arrived'],
    ['p-003', 'pr-005', '10:00', 'booked'],
    ['p-002', 'pr-004', '15:00', 'noshow'],
  ];
  queue.forEach(([pat, pid, t, st], i) => addAppt(`a-q${i + 1}`, pat, pid, at(workday, t), st));

  const encounters: Encounter[] = [
    enc('e-001', 'p-002', 'pr-001', workday.hour(8).minute(5), 'finished', 'Tăng huyết áp tái khám'),
    enc('e-002', 'p-004', 'pr-001', workday.hour(8).minute(40), 'in-progress', 'Hồi hộp, khó thở'),
    enc('e-003', 'p-008', 'pr-003', workday.hour(9).minute(10), 'in-progress', 'Đau thượng vị'),
    enc('e-004', 'p-001', 'pr-003', now.subtract(20, 'day').hour(10), 'finished', 'Khám sức khỏe định kỳ'),
    enc('e-005', 'p-002', 'pr-001', now.subtract(60, 'day').hour(9), 'finished', 'Tăng huyết áp'),
  ];

  const conditions: Condition[] = [
    cond('c-001', 'p-002', 'I10', 'Tăng huyết áp vô căn', '2024-03-02'),
    cond('c-002', 'p-002', 'E11', 'Đái tháo đường type 2', '2022-08-19'),
    cond('c-003', 'p-001', 'J30', 'Viêm mũi dị ứng', '2023-05-10'),
    cond('c-004', 'p-004', 'I48', 'Rung nhĩ', '2025-01-15'),
  ];
  const allergies: AllergyIntolerance[] = [
    allergy('al-001', 'p-002', 'Penicillin', 'high'),
    allergy('al-002', 'p-001', 'Hải sản', 'low'),
  ];
  const observations: Observation[] = [
    vital('o-001', 'p-002', '8480-6', 'Huyết áp tâm thu', 152, 'mm[Hg]', workday.hour(8).minute(10)),
    vital('o-002', 'p-002', '8462-4', 'Huyết áp tâm trương', 94, 'mm[Hg]', workday.hour(8).minute(10)),
    vital('o-003', 'p-002', '8867-4', 'Nhịp tim', 82, '/min', workday.hour(8).minute(10)),
    vital('o-004', 'p-002', '8310-5', 'Nhiệt độ', 36.8, 'Cel', workday.hour(8).minute(10)),
    vital('o-005', 'p-004', '8867-4', 'Nhịp tim', 118, '/min', workday.hour(8).minute(45)),
    vital('o-006', 'p-004', '59408-5', 'SpO₂', 95, '%', workday.hour(8).minute(45)),
  ];

  return { practitioners, roles, patients, slots, appointments, encounters, conditions, allergies, observations };
}

function enc(
  id: string,
  patient: string,
  pid: string,
  start: Dayjs,
  status: Encounter['status'],
  reason: string,
): Encounter {
  return {
    resourceType: 'Encounter',
    id,
    meta: { versionId: '1' },
    status,
    class: { system: 'http://terminology.hl7.org/CodeSystem/v3-ActCode', code: 'AMB', display: 'Ngoại trú' },
    subject: patientRef(patient),
    participant: [{ individual: practitionerRef(pid) }],
    period: { start: start.format(), end: status === 'finished' ? start.add(25, 'minute').format() : undefined },
    reasonCode: [{ text: reason }],
  };
}

function cond(id: string, patient: string, code: string, display: string, onset: string): Condition {
  return {
    resourceType: 'Condition',
    id,
    subject: { reference: `Patient/${patient}` },
    clinicalStatus: { coding: [{ code: 'active' }] },
    code: { coding: [{ system: 'http://hl7.org/fhir/sid/icd-10', code, display }], text: display },
    onsetDateTime: onset,
  };
}

function allergy(id: string, patient: string, text: string, criticality: 'low' | 'high'): AllergyIntolerance {
  return {
    resourceType: 'AllergyIntolerance',
    id,
    patient: { reference: `Patient/${patient}` },
    code: { text },
    criticality,
  };
}

function vital(
  id: string,
  patient: string,
  code: string,
  display: string,
  value: number,
  unit: string,
  at: Dayjs,
): Observation {
  return {
    resourceType: 'Observation',
    id,
    status: 'final',
    category: [{ coding: [{ code: 'vital-signs' }] }],
    code: { coding: [{ system: 'http://loinc.org', code, display }], text: display },
    subject: { reference: `Patient/${patient}` },
    effectiveDateTime: at.format(),
    valueQuantity: { value, unit },
  };
}
