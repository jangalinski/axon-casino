package com.nklmish.demo.managementui

import com.nklmish.demo.managementdata.TotalDeposited
import com.nklmish.demo.walletsummary.*
import com.vaadin.addon.charts.Chart
import com.vaadin.addon.charts.model.*
import com.vaadin.addon.charts.model.style.SolidColor
import com.vaadin.annotations.Push
import com.vaadin.annotations.Theme
import com.vaadin.annotations.Widgetset
import com.vaadin.server.Sizeable
import com.vaadin.server.VaadinRequest
import com.vaadin.spring.annotation.SpringUI
import com.vaadin.ui.HorizontalSplitPanel
import com.vaadin.ui.UI
import com.vaadin.ui.VerticalLayout
import org.axonframework.queryhandling.DefaultQueryGateway
import org.axonframework.queryhandling.QueryGateway
import org.axonframework.queryhandling.SubscriptionQueryResult
import org.axonframework.queryhandling.responsetypes.ResponseTypes
import org.springframework.context.annotation.Profile
import java.math.BigDecimal
import java.util.*

@SpringUI(path = "/management")
@Widgetset("AppWidgetset")
@Theme("mytheme")
@Push
@Profile("gui")
class ManagementUI(private val managementDataCollector: ManagementDataCollector, private val queryGateway: QueryGateway) : UI(), ManagementEventListener {
    private val allSeries: MutableMap<String, DataSeries>
    private val availableSeries = ListSeries("Available")
    private val bettedSeries = ListSeries("Betted")
    private val withdrawingSeries = ListSeries("Withdrawing")
    private var topWalletsChart: Chart? = null
    private var topQueryResult: SubscriptionQueryResult<List<TopWalletSummary>, TopWalletsChange>? = null

    init {
        allSeries = HashMap()
    }

    override fun init(vaadinRequest: VaadinRequest) {
        for (currency in Arrays.asList("EUR", "USD")) {
            val series = DataSeries()
            series.plotOptions = PlotOptionsSpline()
            series.name = "$currency deposits"
            allSeries[currency] = series
        }
        managementDataCollector.register(this)

        val totalsColumn = VerticalLayout()
        totalsColumn.setSizeFull()
        for (chart in totalDepositedCharts()) totalsColumn.addComponent(chart)

        val walletsColumn = VerticalLayout()
        walletsColumn.setSizeFull()
        this.topWalletsChart = topWalletsChart()
        walletsColumn.addComponent(topWalletsChart)

        val splitPanel = HorizontalSplitPanel()
        splitPanel.setSplitPosition(50f, Sizeable.Unit.PERCENTAGE)
        splitPanel.firstComponent = totalsColumn
        splitPanel.secondComponent = walletsColumn

        content = splitPanel

        initTopWalletData()
    }

    override fun close() {
        managementDataCollector.unregister(this)
        topQueryResult?.cancel()
        super.close()
    }

    override fun updateTotals(now: Long, totals: List<TotalDeposited>) {
        access {
            for ((currency, amount) in totals) {
                val series = allSeries[currency]
                val shift = series!!.size() > 20
                series.add(DataSeriesItem(now, amount), true, shift)
            }
        }
    }

    private fun initTopWalletData() {
        val queryResult = queryGateway.subscriptionQuery(TopWalletSummaryQuery(),
                ResponseTypes.multipleInstancesOf(TopWalletSummary::class.java),
                ResponseTypes.instanceOf(TopWalletsChange::class.java))
        topQueryResult = queryResult
        queryResult.initialResult().subscribe(::processNewTopMembers)
        queryResult.updates().subscribe { change ->
            when(change) {
                is TopWalletsMemberChange -> processNewTopMembers(change.summaries)
                is TopWalletsValueChange -> processTopDataChange(change.position, change.summary)
            }
        }
    }

    private fun processNewTopMembers(top: List<TopWalletSummary>) {
        val categories = arrayOfNulls<String>(top.size)
        val available = arrayOfNulls<Number>(top.size)
        val betted = arrayOfNulls<Number>(top.size)
        val withdrawing = arrayOfNulls<Number>(top.size)
        for (i in top.indices) {
            categories[i] = top[i].walletId
            available[i] = top[i].available
            betted[i] = top[i].betted
            withdrawing[i] = top[i].withdrawing
        }
        access {
            val configuration = topWalletsChart!!.configuration
            configuration.getxAxis().setCategories(*categories)
            availableSeries.setData(*available)
            bettedSeries.setData(*betted)
            withdrawingSeries.setData(*withdrawing)
            topWalletsChart!!.drawChart(configuration)
        }
    }

    private fun processTopDataChange(pos: Int, data: TopWalletSummary) {
        access {
            availableSeries.updatePoint(pos, data.available)
            bettedSeries.updatePoint(pos, data.betted)
            withdrawingSeries.updatePoint(pos, data.withdrawing)
        }

    }

    private fun totalDepositedCharts(): List<Chart> {
        val charts = ArrayList<Chart>()
        for (currency in allSeries.keys) {
            val chart = Chart()
            val configuration = chart.configuration
            configuration.chart.type = ChartType.SPLINE
            configuration.title.text = "Total deposited $currency"

            val xAxis = configuration.getxAxis()
            xAxis.type = AxisType.DATETIME
            xAxis.tickPixelInterval = 150

            val yAxis = configuration.getyAxis()
            yAxis.title = AxisTitle("Amount")
            yAxis.min = 0

            configuration.tooltip.enabled = false
            configuration.legend.enabled = false
            configuration.addSeries(allSeries[currency])

            chart.setSizeFull()

            charts.add(chart)
        }
        return charts
    }

    private fun topWalletsChart(): Chart {
        val chart = Chart(ChartType.BAR)
        chart.setSizeFull()

        val conf = chart.configuration

        conf.setTitle("Top Wallets")

        val x = XAxis()
        x.setCategories()
        conf.addxAxis(x)

        val y = YAxis()
        y.min = 0
        y.setTitle("Value (converted to EUR)")
        conf.addyAxis(y)

        val legend = Legend()
        legend.backgroundColor = SolidColor("#FFFFFF")
        legend.reversed = true

        val plot = PlotOptionsSeries()
        plot.stacking = Stacking.NORMAL
        conf.setPlotOptions(plot)

        conf.addSeries(availableSeries)
        conf.addSeries(bettedSeries)
        conf.addSeries(withdrawingSeries)

        chart.drawChart(conf)

        return chart
    }

}
