package com.example.usb_sector_rw

import CmdToPram
import FmFrecStruct
import FmPhasesStruct
import SectorAnswer
import SectorCmd
import android.R.attr.min
import com.example.usb_sector_rw.msd.LospDev
import java.io.PrintWriter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt


var answer = SectorAnswer()
var cycle_ok : ULong = 0u
var fix_count : ULong = 0u
private var handleOut: PrintWriter? = null
private var tickBegin: Long = 0
private var cycle = 0

var accuracy : Float = 0f
var frec_current : Float = 0f
var frec_old : Float = 0f
var period_av : Float = 1f
var frec_av : Float = 0f
var frec_quad : Float = 0f
var frec_min : Float = 9e9f
var frec_max : Float = 0f
var frec_rms : Float = 0f
var m_sum : ULong = 0u
var cycle_av : ULong = 0u
var fix_count_exec : ULong = 0u
var tick_stop : ULong = 0u
var period_quad : Float = 0f
var period_rms : Float = 0f
var frec_acc : Float = 0f
var period_acc : Float = 0f
var period_acc_old : Float = 0f

/**
 * Выполняет запрос частоты от микроконтроллера и выводит результат в лог или интерфейс.
 * Вывод в файл отключён — закомментирован.
 *
 * @param printfCount Счётчик количества вызовов (используется для выбора форматирования)
 * @return Обновлённый счётчик printf
 * @author Sergey Rundygin
 */
@OptIn(ExperimentalUnsignedTypes::class)
fun frecUc(printfCount: Int, lospDev : LospDev, log: (String) -> Unit): Float {
    val cmd = SectorCmd()
    var freq: Float = 0f
    var p = FmFrecStruct.fromByteArray(ubyteArrayOf(0x0u))

    cmd.code = CmdToPram.PRAM_GET_FW_FREC.value
    cmd.sizeOut = FmFrecStruct.SIZE_BYTES.toUShort()

    if(!lospDev.lospExecCmd(cmd, log))
    {
        freq = -3.0f
    }
    else
    {
        if(!lospDev.getLospAnswer(cmd.code, answer, log))
        {
            freq = -4.0f
        }
        else
        {
            p = FmFrecStruct.fromByteArray(answer.dataOut)
            freq = p.frecAverage
        }
    }


    return freq
}

@OptIn(ExperimentalUnsignedTypes::class)
fun frec_test(lospDev : LospDev, log: (String) -> Unit) : Boolean
{
    var loss : Boolean = false;
    val cmd = SectorCmd()
    var p = FmPhasesStruct.fromByteArrayToFmPhasesStruct(ubyteArrayOf(0x0u))

    cmd.code = CmdToPram.PRAM_GET_FW_PHASES.value
    cmd.sizeOut = FmPhasesStruct.SIZE_BYTES.toUShort()

    if(lospDev.lospExecCmd(cmd, log))
    {
        if(lospDev.getLospAnswer(cmd.code, answer, log))
        {
            p = FmPhasesStruct.fromByteArrayToFmPhasesStruct(answer.dataOut)
            loss = ((cycle_ok.toInt() != 0)|| (fix_count.toInt() != 0))
                    && ((p.fixCount.toInt() != 0) > ((fix_count.toInt() + p.validSize.toInt()) != 0));
            cycle_ok++
            if (loss && cycle_ok.toInt() != 0)
            {
                log(("\n\rПотеряны зафиксированные фазы таймеров\n\r"))
            }
            if (!loss )
            {
                log("Tест частотомера № $cycle_ok - Ok")
            }

            fix_count =  p.fixCount.toULong()
        }
    }

    return loss;
}

@OptIn(ExperimentalUnsignedTypes::class)
fun frec_exec(lospDev : LospDev, log: (String) -> Unit)
{
    var p = FmPhasesStruct.fromByteArrayToFmPhasesStruct(answer.dataOut)
    val cmd = SectorCmd()

    cmd.code = CmdToPram.PRAM_GET_FW_PHASES.value
    cmd.sizeOut = FmPhasesStruct.SIZE_BYTES.toUShort()

    lospDev.lospExecCmd(cmd, log)
    lospDev.getLospAnswer(cmd.code, answer, log)

    if((p.validSize.toInt() != 0) && (fix_count_exec.toUInt() != p.fixCount))
    {
        var count = if (fix_count == 0UL) {
            p.validSize.toInt()
        } else {
            minOf((p.fixCount - fix_count).toInt(), p.maxSize.toInt())
        }

        var index : Int = p.validSize.toInt() - 0;
        fix_count_exec = p.fixCount.toULong();

        while ((count-- > 0) && (--index > 0))
        {
            var m : ULong = p.phases[index * 2].toULong()  - p.phases[index * 2 - 2].toULong()
            var n : ULong = p.phases[index * 2 + 1].toULong()  - p.phases[index * 2 - 1].toULong()

            if(n.toInt() == 0)
            {
                log("\n\rНет сигнала на входе частотомера\n\r");
                cycle_av = 0u
                return
            }

            m_sum += m
            var fm_SystemCoreClock : Float = 16000000f
            fm_SystemCoreClock = p.systemClock.toFloat();
            frec_current = (fm_SystemCoreClock * m.toFloat()) / n.toFloat();

            period_av = (period_av * cycle_av.toFloat() + 1 / frec_current) / (cycle_av.toFloat() + 1);
            frec_av   = 1 / period_av;

            frec_min = min(frec_min, frec_current)
            frec_max = max(frec_max, frec_current)
            frec_quad += frec_current * frec_current
            period_quad += (1.0 / frec_current).pow(2).toFloat()
            cycle_av++

            frec_rms = sqrt(1e-11f + frec_quad / cycle_av.toFloat() - frec_av.pow(2)).toFloat()
            frec_rms /= sqrt(1e-11f + cycle_av.toFloat()).toFloat()
            frec_acc = 100.0f * frec_rms / frec_av

            period_rms = sqrt(1e-11f + period_quad / cycle_av.toFloat() - period_av.pow(2)).toFloat()
            period_rms /= sqrt(1e-11f + cycle_av.toFloat()).toFloat()
            period_acc = 100.0f * period_rms / period_av

            if(tick_stop.toInt() == 0)
            {
                frec_old = frec_av;
                // frec to file TODO
                // tick_stop = tick_end( pcmd_option->init( _T("-a" ), _T("5") ) ); TODO
            }

            if (((accuracy < 1e-99))/* && !timeOut_ok( tick_stop )) TODO*/
                || ((accuracy > 0) && (cycle_av > max (1f, 1e3f / (accuracy * accuracy)).toUInt()) && (period_acc < accuracy)))
            {
                frec_old = frec_av;
                period_acc_old = period_acc;
                // frec to file TODO
//                tick_stop += (u64)(0.5 + 1e6 * max( 0.01, atof( pcmd_option->init( _T("-a" ), _T("5") ) ) )); TODO
                m_sum = 0u
            }
        }
    }

    if (period_acc_old.toInt() == 0)
    {
        log("Частота = $frec_old Гц")
    }
    else if(accuracy < 1e-99)
    {
        log("Частота = $frec_old +- ${frec_old * period_acc_old / 1e2} Гц")
    }
    else
    {
        log("Частота = $frec_old +- $period_acc_old Гц")
    }
}